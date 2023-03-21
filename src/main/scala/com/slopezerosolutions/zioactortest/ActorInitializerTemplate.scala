package com.slopezerosolutions.zioactortest

import zio.{Task, ZIO}

abstract class ActorInitializerTemplate[T] extends ActorInitializer[T] {
  type MessageType

  override def initialize: Task[(ActorTemplate[MessageType], (messageDestination: MessageDestination[MessageType], directory: T) => Task[T])] =
    for {
      actorTemplateValue <- actorTemplate
    } yield (actorTemplateValue, (messageDestination, directory) => for {
      directory <- ZIO.succeed(injectActorReference(messageDestination, directory))
    } yield directory)
  def actorTemplate: Task[ActorTemplate[MessageType]]
  def injectActorReference(messageDestination: MessageDestination[MessageType], directory: T): T = {
    directory
  }
}
