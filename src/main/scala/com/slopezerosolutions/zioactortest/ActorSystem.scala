package com.slopezerosolutions.zioactortest

import zio._
class ActorSystem {

  def promiseMessageDestination[T](promise: Promise[Throwable, T]): MessageDestination[T] = {
    PromiseMessageDestination(promise)
  }

  def send[T](message: T, messageDestination: MessageDestination[T]): UIO[Boolean] = {
    messageDestination match {
      case PromiseMessageDestination(promise) => promise.succeed(message)
    }
  }
}
