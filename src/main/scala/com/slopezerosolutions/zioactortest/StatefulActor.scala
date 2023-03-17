package com.slopezerosolutions.zioactortest

object StatefulActor {
  abstract sealed class Result[S]
  case class Continue[S]() extends Result[S]
  case class UpdateState[S](newState: S) extends Result[S]
}
