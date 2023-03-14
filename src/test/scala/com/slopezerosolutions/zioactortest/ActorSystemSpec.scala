package com.slopezerosolutions.zioactortest

import zio.test._

class ActorSystemSpec extends zio.test.junit.JUnitRunnableSpec {

  def spec = suite("ActorSystem tests")(
    test("can send a message") {
      assert(true)(Assertion.equalTo(true))
    }
  )
}
