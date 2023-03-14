scalaVersion := "3.2.2"
organization := "com.slopezerosolutions.zioactortest"
name := "zio-actor-test"

lazy val zioVersion = "2.0.10"

libraryDependencies ++= Seq(
  "dev.zio" %% "zio" % zioVersion,
  "dev.zio" %% "zio-test" % zioVersion,
  "dev.zio" %% "zio-test-sbt" % zioVersion,
  "dev.zio" %% "zio-test-junit" % zioVersion,
)

testFrameworks += new TestFramework("zio.test.sbt.ZTestFramework")