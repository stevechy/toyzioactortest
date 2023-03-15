package com.slopezerosolutions.zioactortest

import zio.Task

abstract class ActorInitializer[T] {
  type MessageType
  def actorTemplate: Task[ActorTemplate[MessageType]]
  def injectActorReference(messageDestination: MessageDestination[MessageType], directory: T): T
}
