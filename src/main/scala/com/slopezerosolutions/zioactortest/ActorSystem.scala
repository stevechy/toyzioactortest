package com.slopezerosolutions.zioactortest

import zio.*
import zio.stm.{STM, TMap}

import java.security.SecureRandom

object ActorSystem {

  def initialize: Task[ActorSystem[Unit]] = initialize((), List())
  def initialize[D](initialDirectory: D, initializers: List[ActorInitializer[D]]): Task[ActorSystem[D]] = for {
    actors <- STM.atomically(TMap.empty[String, Actor[Nothing]])
    newDirectory <- ZIO.foldLeft(initializers)(initialDirectory)(
      (directory: D, initializer: ActorInitializer[D]) => for {
        template <- initializer.actorTemplate
        newActor <- createActorState(template)
        actorMessageDestination <- registerActor(newActor, actors)
        newDirectory <- ZIO.succeed(initializer.injectActorReference( actorMessageDestination, directory))
      } yield newDirectory)
  } yield new ActorSystem(actors, newDirectory)

  private def createActorState[T](actorTemplate: ActorTemplate[T]): Task[Actor[T]] = actorTemplate match {
    case HandlerActorTemplate(handler) =>
      for {
        inbox <- zio.Queue.bounded[T](100)
        actor <- ZIO.succeed(new Actor(inbox))
        _ <- actorLoop(actor, handler).forkDaemon
      } yield actor
  }

  private def registerActor[T](actor: Actor[T], actors: TMap[String, Actor[Nothing]]): Task[MessageDestination[T]] = for {
    actorId <- ZIO.succeed(java.util.UUID.randomUUID().toString)
    _ <- STM.atomically {
      actors.put(actorId, actor.asInstanceOf[Actor[Nothing]])
    }
  } yield ActorMessageDestination[T](actorId)

  private def actorLoop[T](actor: Actor[T], handler: T => Task[Boolean]): Task[Boolean] = {
    val handleMessage: Boolean => Task[Boolean] = (state: Boolean) => for {
      message <- actor.inbox.take
      _ <- handler(message)
    } yield true
    for {
      _ <- ZIO.iterate[Any, Throwable, Boolean](true)(_ != false)(handleMessage)
    } yield true
  }
}

class ActorSystem[D] private(actors: TMap[String, Actor[Nothing]], val directory: D) {
  def promiseMessageDestination[T](promise: Promise[Throwable, T]): MessageDestination[T] = {
    PromiseMessageDestination(promise)
  }

  def adaptedMessageDestination[I, O](adapter: I => O, messageDestination: MessageDestination[O]): MessageDestination[I] = {
    AdaptedMessageDestination(adapter, messageDestination)
  }

  def startActor[T](handler: T => Task[Boolean]): Task[MessageDestination[T]] = for {
    actor <- ActorSystem.createActorState(HandlerActorTemplate(handler))
    actorMessageDestination <- ActorSystem.registerActor(actor, actors)
  } yield actorMessageDestination

  def send[T](message: T, messageDestination: MessageDestination[T]): Task[Boolean] = {
    messageDestination match {
      case PromiseMessageDestination(promise) => promise.succeed(message)
      case AdaptedMessageDestination(adapter, messageDestination) =>
        val adaptedMessage = adapter.apply(message)
        send(adaptedMessage, messageDestination)
      case ActorMessageDestination(actorId) => for {
        actorOption <- STM.atomically(actors.get(actorId))
        actor <- ZIO.getOrFail(actorOption)
        _ <- actor.asInstanceOf[Actor[T]].inbox.offer(message)
      } yield true
    }
  }
}
