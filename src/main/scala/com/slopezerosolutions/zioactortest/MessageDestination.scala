package com.slopezerosolutions.zioactortest

sealed abstract class MessageDestination[T]
case class PromiseMessageDestination[T](promise: zio.Promise[Throwable, T], actorService: ActorService) extends MessageDestination[T]
case class AdaptedMessageDestination[I,O](adapter: I => O, messageDestination: MessageDestination[O], actorService: ActorService) extends MessageDestination[I]
case class ActorMessageDestination[T](actorId: String, actorService: ActorService) extends MessageDestination[T]