package com.slopezerosolutions.zioactortest

import zio.*
import zio.stm.{STM, TMap}

import java.security.SecureRandom

object ActorSystem {

  def initialize: Task[ActorSystem[Unit]] = initialize((), List())

  def initialize[D](initialDirectory: D, initializers: List[ActorInitializer[D]]): Task[ActorSystem[D]] = for {
    actors <- STM.atomically(TMap.empty[String, Actor[Nothing]])
    directoryRef <- Ref.make(initialDirectory)
    actorSystem <- ZIO.succeed(new ActorSystem(actors, directoryRef))
    newDirectory <- ZIO.foldLeft(initializers)(initialDirectory)(
      (directory: D, initializer: ActorInitializer[D]) => for {
        initializerResult <- initializer.initialize
        (template, injector) = initializerResult
        newActor <- createActorState(actorSystem, template, true)
        actorMessageDestination <- actorSystem.registerActor(newActor, actors)
        newDirectory <- injector(actorMessageDestination, directory)
      } yield newDirectory)
    _ <- directoryRef.set(newDirectory)
  } yield actorSystem

  private def createActorState[T](actorCreator: ActorService, actorTemplate: ActorTemplate[T], daemonFibre: Boolean): Task[Actor[T]] = actorTemplate match {
    case HandlerActorTemplate(handler) =>
      for {
        inbox <- zio.Queue.bounded[T](100)
        actor <- ZIO.succeed(new Actor(inbox))
        _ <- if (daemonFibre)
          actorLoop(actorCreator, actor, handler).forkDaemon
        else
          actorLoop(actorCreator, actor, handler).fork
      } yield actor
    case template: StatefulActorTemplate[T] => for {
      inbox <- zio.Queue.bounded[T](100)
      actor <- ZIO.succeed(new Actor(inbox))
      initialState = template.initialStateSupplier.apply()
      handler  = template.handler
      _ <- if (daemonFibre)
        statefulActorLoop(actorCreator, actor, initialState, handler).forkDaemon
      else
        statefulActorLoop(actorCreator, actor, initialState, handler).fork
    } yield actor
  }

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

class ActorSystem[D] private(actors: TMap[String, Actor[Nothing]], private val directoryRef: Ref[D]) extends ActorService {

  def directory: UIO[D] = directoryRef.get

  def promiseMessageDestination[T](promise: Promise[Throwable, T]): MessageDestination[T] = {
    PromiseMessageDestination(promise)
  }

  def adaptedMessageDestination[I, O](adapter: I => O, messageDestination: MessageDestination[O]): MessageDestination[I] = {
    messageDestination.adaptedMessageDestination(adapter)
  }

  private def registerActor[T](actor: Actor[T], actors: TMap[String, Actor[Nothing]]): Task[MessageDestination[T]] = for {
    actorId <- ZIO.succeed(java.util.UUID.randomUUID().toString)
    _ <- STM.atomically {
      actors.put(actorId, actor.asInstanceOf[Actor[Nothing]])
    }
  } yield new ActorMessageDestination[T](actorId, this) {
    def send(message: T): Task[Boolean] = for {
      actorOption <- STM.atomically(actors.get(actorId))
      actor <- ZIO.getOrFail(actorOption)
      _ <- actor.asInstanceOf[Actor[T]].inbox.offer(message)
    } yield true
  }

  def startActor[T](handler: T => Task[Boolean]): Task[MessageDestination[T]] = {
    val template = ActorTemplate.handler(handler)
    startActor(template)
  }

  override def startActor[T](template: ActorTemplate[T]): Task[MessageDestination[T]] = {
    for {
      actor <- ActorSystem.createActorState(this, template, true)
      actorMessageDestination <- registerActor(actor, actors)
    } yield actorMessageDestination
  }
}
