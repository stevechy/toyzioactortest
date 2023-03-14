package com.slopezerosolutions.zioactortest

import zio.*
import zio.stm.{STM, TMap}

import java.security.SecureRandom

object ActorSystem {
  def initialize: UIO[ActorSystem] = for {
    actors <- STM.atomically(TMap.empty[String, Actor[Nothing]])
  } yield new ActorSystem(actors)
}

class ActorSystem private(actors: TMap[String, Actor[Nothing]]) {

  def promiseMessageDestination[T](promise: Promise[Throwable, T]): MessageDestination[T] = {
    PromiseMessageDestination(promise)
  }

  def adaptedMessageDestination[I, O](adapter: I => O, messageDestination: MessageDestination[O]): MessageDestination[I] = {
    AdaptedMessageDestination(adapter, messageDestination)
  }

  def startActor[T](handler: T => Task[Boolean]): UIO[MessageDestination[T]] = for {
    actorId <- ZIO.succeed(java.util.UUID.randomUUID().toString)
    inbox <- zio.Queue.bounded[T](100)
    actor <- ZIO.succeed(new Actor(inbox))
    _ <- actorLoop(actor, handler).forkDaemon
    _ <- STM.atomically {
      actors.put(actorId, actor.asInstanceOf[Actor[Nothing]])
    }
  } yield ActorMessageDestination[T](actorId)

  private def actorLoop[T](actor: Actor[T], handler: T => Task[Boolean]): Task[Boolean] = {
    val handleMessage: Boolean => Task[Boolean] = (state: Boolean) => for {
      message <- actor.inbox.take
      _ <- handler(message)
    } yield true
    val function: Boolean => Boolean = _ != false
    for {
      _ <- ZIO.loopDiscard[Any,Throwable,Boolean](true)(function, identity)(handleMessage)
    } yield true
  }

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
