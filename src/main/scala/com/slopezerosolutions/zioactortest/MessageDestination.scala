package com.slopezerosolutions.zioactortest

sealed abstract class MessageDestination[T]
case class PromiseMessageDestination[T](promise: zio.Promise[Throwable, T]) extends MessageDestination[T]