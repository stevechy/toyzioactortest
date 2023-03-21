package com.slopezerosolutions.zioactortest

import zio.{Promise, Task}


object MessageDestination {
  def promise[T](handler: MessageDestination[T] => Task[Any]): Task[Promise[Throwable, T]] = for {
    resultPromise <- Promise.make[Throwable, T]
    _ <- handler(PromiseMessageDestination(resultPromise))
  } yield resultPromise
}

sealed abstract class MessageDestination[T]{
  def send(message: T): Task[Boolean]

  def adaptedMessageDestination[I](adapter: I => T): MessageDestination[I] = {
    new AdaptedMessageDestination[I, T](adapter, this)
  }
}
case class PromiseMessageDestination[T](promise: zio.Promise[Throwable, T]) extends MessageDestination[T] {
  def send(message: T): Task[Boolean] = {
    promise.succeed(message)
  }
}
case class AdaptedMessageDestination[I,O](adapter: I => O, messageDestination: MessageDestination[O]) extends MessageDestination[I] {
  def send(message: I): Task[Boolean] = {
    val adaptedMessage = adapter.apply(message)
    messageDestination.send(adaptedMessage)
  }
}
abstract class ActorMessageDestination[T](val actorId: String, actorService: ActorSystem[_]) extends MessageDestination[T]