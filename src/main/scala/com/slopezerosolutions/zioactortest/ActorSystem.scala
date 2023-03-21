package com.slopezerosolutions.zioactortest

import zio.*
import zio.stm.{STM, TMap, TRef}

import java.security.SecureRandom

object ActorSystem {

  def initialize: Task[ActorSystem[Unit]] = initialize((), List())

  def initialize[D](initialDirectory: D, initializers: List[ActorInitializer[D]]): Task[ActorSystem[D]] = for {
    actors <- STM.atomically(TMap.empty[String, TRef[ActorState[Nothing]]])
    directoryRef <- Ref.make(initialDirectory)
    actorSystem <- ZIO.succeed(new ActorSystem(actors, directoryRef))
    newDirectory <- ZIO.foldLeft(initializers)(initialDirectory)(
      (directory: D, initializer: ActorInitializer[D]) => for {
        initializerResult <- initializer.initialize
        (template, injector) = initializerResult
        actorMessageDestination <- actorSystem.startActor(template)
        newDirectory <- injector(actorMessageDestination, directory)
      } yield newDirectory)
    _ <- directoryRef.set(newDirectory)
  } yield actorSystem


  private def actorLoop[T](actorCreator: ActorService, actor: Actor[T], handler: (ActorService, T) => Task[Boolean]): Task[Boolean] = {
    val handleMessage: Boolean => Task[Boolean] = (state: Boolean) => for {
      message <- actor.inbox.take
      _ <- handler(actorCreator, message)
    } yield true
    for {
      _ <- ZIO.iterate[Any, Throwable, Boolean](true)(_ != false)(handleMessage)
    } yield true
  }

  private def statefulActorLoop[S,T](actorCreator: ActorService, actor: Actor[T], state: S, handler: (ActorService, T, S) => Task[StatefulActor.Result[S]]): Task[Boolean] = {
    val handleMessage = for {
      message <- actor.inbox.take
      result <- handler(actorCreator, message, state)
    } yield result
    handleMessage.flatMap {
      case StatefulActor.Continue() => statefulActorLoop(actorCreator, actor, state, handler)
      case StatefulActor.UpdateState(newState) => statefulActorLoop(actorCreator, actor, newState, handler)
    }
  }
}

class ActorSystem[D] private(actors: TMap[String, TRef[ActorState[Nothing]]], private val directoryRef: Ref[D]) {

  def directory: UIO[D] = directoryRef.get

  def promiseMessageDestination[T](promise: Promise[Throwable, T]): MessageDestination[T] = {
    PromiseMessageDestination(promise)
  }

  def adaptedMessageDestination[I, O](adapter: I => O, messageDestination: MessageDestination[O]): MessageDestination[I] = {
    messageDestination.adaptedMessageDestination(adapter)
  }

  private def registerActor[T](actorState: ActorState[T]): Task[MessageDestination[T]] = for {
    actorId <- ZIO.succeed(actorState.actor.actorId)
    _ <- STM.atomically {
      for {
        _ <- if (actorState.parent.isDefined)
          for {
            actorRefOption <- actors.get(actorState.parent.get)
            _ <- actorRefOption match {
              case Some(actorRef) => actorRef.update(actorState => {
                // Parent could be stopped or restarting
                actorState.copy(children = actorState.children + actorId)
              })
              case None => STM.succeed(())
            }
          } yield ()
        else
          STM.succeed(())
        actorStateRef <- TRef.make(actorState.asInstanceOf[ActorState[Nothing]])
        _ <- actors.put(actorId, actorStateRef)
      } yield ()
    }
  } yield new ActorMessageDestination[T](actorId, this) {
    def send(message: T): Task[Boolean] = for {
      actorOption <- STM.atomically(actors.get(this.actorId))
      actorRef <- ZIO.getOrFail(actorOption)
      actorState <- STM.atomically(actorRef.get)
      _ <- actorState.actor.asInstanceOf[Actor[T]].inbox.offer(message)
    } yield true
  }

  private def createActor[T](actorTemplate: ActorTemplate[T], parentActor: Option[String]): Task[ActorState[T]] = {
    val actorId = java.util.UUID.randomUUID().toString
    val actorSystem = this
    val actorService = new ActorService {
      override def startActor[M](template: ActorTemplate[M]): Task[MessageDestination[M]] = {
        for {
          actor <- createActor(template, Some(actorId))
          actorMessageDestination <- registerActor(actor)
        } yield actorMessageDestination
      }

      override def stopActor[M](messageDestination: MessageDestination[M]): Task[Unit] = {
        actorSystem.stopActor(messageDestination)
      }
    }
    actorTemplate match {
      case HandlerActorTemplate(handler) =>
        for {
          inbox <- zio.Queue.bounded[T](100)
          actor <- ZIO.succeed(new Actor(actorId, inbox))
          actorFibre <- if (parentActor.isDefined)
            ActorSystem.actorLoop(actorService, actor, handler).forkDaemon
          else
            ActorSystem.actorLoop(actorService, actor, handler).fork
        } yield ActorState(phase = ActorState.Running(), parent = parentActor, children = Set(), actor = actor, fiber = actorFibre)
      case template: StatefulActorTemplate[T] => for {
        inbox <- zio.Queue.bounded[T](100)
        actor <- ZIO.succeed(new Actor(actorId, inbox))
        initialState = template.initialStateSupplier.apply()
        handler = template.handler
        actorFibre <- if (parentActor.isDefined)
          ActorSystem.statefulActorLoop(actorService, actor, initialState, handler).forkDaemon
        else
          ActorSystem.statefulActorLoop(actorService, actor, initialState, handler).fork
      } yield ActorState(phase = ActorState.Running(), parent = parentActor, children = Set(), actor = actor, fiber = actorFibre)
    }
  }

  def stopActor[T](messageDestination: MessageDestination[T]): Task[Unit] = messageDestination match {
    case actorDestination: ActorMessageDestination[T] => for {
      _ <- suspendActorById(actorDestination.actorId, ActorState.Stopped())
      _ <- STM.atomically(actors.delete(actorDestination.actorId))
    } yield ()
    case _ => ZIO.succeed(())
  }

  def activeActorDestination[T](messageDestination: MessageDestination[T]): Task[Boolean] =
    messageDestination match {
      case actorDestination: ActorMessageDestination[T] =>
        STM.atomically { actors.contains(actorDestination.actorId) }
      case _ => ZIO.succeed(false)
    }

  private def suspendActorById(actorId: String, nextPhase: ActorState.Phase): Task[Option[ActorState[Nothing]]] =
    for {
      actorState <- STM.atomically {
        for {
          actorStateOptional <- actors.get(actorId)
          updatedState <- actorStateOptional match {
            case Some(actorStateRef) => for {
              actorState <- actorStateRef.get
              newActorState = actorState.copy(phase = nextPhase)
              _ <- actorStateRef.set(newActorState)
            } yield Some(newActorState)
            case None => STM.succeed(None)
          }
        } yield updatedState
      }
      _ <- actorState match {
        case Some(actorState) => (for {
          _ <- actorState.fiber.interrupt
          _ <- actorState.actor.inbox.shutdown
        } yield ()).fork
        case None => ZIO.succeed(())
      }
      _ <- ZIO.foreachDiscard(actorState.toList.flatMap(_.children))(childActorId => for {
        _ <- suspendActorById(childActorId, ActorState.Stopped())
        _ <- STM.atomically { actors.delete(childActorId) }
      } yield ())
    } yield actorState

  def startActor[T](handler: T => Task[Boolean]): Task[MessageDestination[T]] = {
    val template = ActorTemplate.handler(handler)
    startActor(template)
  }

  def startActor[T](template: ActorTemplate[T]): Task[MessageDestination[T]] = {
    for {
      actor <- createActor(template, None)
      actorMessageDestination <- registerActor(actor)
    } yield actorMessageDestination
  }
}
