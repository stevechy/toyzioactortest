package com.slopezerosolutions.zioactortest

sealed abstract class MessageDestination[T]
case class PromiseMessageDestination[T](promise: zio.Promise[Throwable, T]) extends MessageDestination[T]
case class AdaptedMessageDestination[I,O](adapter: I => O, messageDestination: MessageDestination[O]) extends MessageDestination[I]
case class ActorMessageDestination[T](actorId: String) extends MessageDestination[T]