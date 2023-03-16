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
        template <- initializer.actorTemplate
        newActor <- createActorState(actorSystem, template, true)
        actorMessageDestination <- actorSystem.registerActor(newActor, actors)
        newDirectory <- ZIO.succeed(initializer.injectActorReference(actorMessageDestination, directory))
      } yield newDirectory)
    _ <- directoryRef.set(newDirectory)
  } yield actorSystem

  private def createActorState[T](actorCreator: ActorService, actorTemplate: ActorTemplate[T], daemonFibre: Boolean): Task[Actor[T]] = actorTemplate match {
    case HandlerActorTemplate(handler) =>
      for {
        inbox <- zio.Queue.bounded[T](100)
        actor <- ZIO.succeed(new Actor(inbox))
        _ <- if (daemonFibre)
          actorLoop (actorCreator, actor, handler).forkDaemon
        else
          actorLoop(actorCreator, actor, handler).fork
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
}

class ActorSystem[D] private(actors: TMap[String, Actor[Nothing]], private val directoryRef: Ref[D]) extends ActorService {

  def directory: UIO[D] = directoryRef.get

  def promiseMessageDestination[T](promise: Promise[Throwable, T]): MessageDestination[T] = {
    PromiseMessageDestination(promise, this)
  }

  def adaptedMessageDestination[I, O](adapter: I => O, messageDestination: MessageDestination[O]): MessageDestination[I] = {
    AdaptedMessageDestination(adapter, messageDestination, this)
  }

  def withPromiseMessageDestination[T](handler: MessageDestination[T] => Task[Unit]): Task[Promise[Throwable, T]] = for {
    resultPromise <- Promise.make[Throwable, T]
    _ <- handler(promiseMessageDestination(resultPromise))
  } yield resultPromise

  private def registerActor[T](actor: Actor[T], actors: TMap[String, Actor[Nothing]]): Task[MessageDestination[T]] = for {
    actorId <- ZIO.succeed(java.util.UUID.randomUUID().toString)
    _ <- STM.atomically {
      actors.put(actorId, actor.asInstanceOf[Actor[Nothing]])
    }
  } yield ActorMessageDestination[T](actorId, this)

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

  def send[T](message: T, messageDestination: MessageDestination[T]): Task[Boolean] = {
    messageDestination match {
      case PromiseMessageDestination(promise, _) => promise.succeed(message)
      case AdaptedMessageDestination(adapter, messageDestination, _) =>
        val adaptedMessage = adapter.apply(message)
        send(adaptedMessage, messageDestination)
      case ActorMessageDestination(actorId, _) => for {
        actorOption <- STM.atomically(actors.get(actorId))
        actor <- ZIO.getOrFail(actorOption)
        _ <- actor.asInstanceOf[Actor[T]].inbox.offer(message)
      } yield true
    }
  }
}
