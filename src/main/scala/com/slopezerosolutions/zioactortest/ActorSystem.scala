package com.slopezerosolutions.zioactortest

import zio._
class ActorSystem {

  def promiseMessageDestination[T](promise: Promise[Throwable, T]): MessageDestination[T] = {
    PromiseMessageDestination(promise)
  }

  def adaptedMessageDestination[I,O](adapter: I => O, messageDestination: MessageDestination[O]): MessageDestination[I] = {
    AdaptedMessageDestination(adapter, messageDestination)
  }

  def send[T](message: T, messageDestination: MessageDestination[T]): UIO[Boolean] = {
    messageDestination match {
      case PromiseMessageDestination(promise) => promise.succeed(message)
      case AdaptedMessageDestination(adapter, messageDestination) => {
        val adaptedMessage = adapter.apply(message)
        send(adaptedMessage, messageDestination)
      }
    }
  }
}
