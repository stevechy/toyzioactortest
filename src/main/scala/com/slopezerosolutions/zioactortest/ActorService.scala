package com.slopezerosolutions.zioactortest

import zio.Task

trait ActorService {
  def startActor[T](template: ActorTemplate[T]): Task[MessageDestination[T]]

  def send[T](message: T, messageDestination: MessageDestination[T]): Task[Boolean]
}
