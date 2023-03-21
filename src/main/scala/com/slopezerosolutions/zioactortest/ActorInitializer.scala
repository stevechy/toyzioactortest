package com.slopezerosolutions.zioactortest

import zio.Task

abstract class ActorInitializer[T] {
  type MessageType
  def initialize: Task[(ActorTemplate[MessageType], (MessageDestination[MessageType],T) => Task[T])]
}
