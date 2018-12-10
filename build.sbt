name         := "akka-cqrs-dsl"
organization := "io.digital-magic"
version      := "2.0-M3"  // don't forget to bump version in .bintray.json

organizationHomepage := Some(url("http://www.digital-magic.io"))
startYear            := Some(2016)

scalaVersion       := Dependencies.Versions.scala212
crossScalaVersions := Seq(Dependencies.Versions.scala212)

parallelExecution in Test := false

resolvers += Resolver.sonatypeRepo("releases")

addCompilerPlugin("org.scalamacros" % "paradise" % "2.1.0" cross CrossVersion.full)
addCompilerPlugin("org.spire-math" % "kind-projector" % "0.9.8" cross CrossVersion.binary)

scalacOptions ++= Seq(
  "-feature",
  "-deprecation",
  "-unchecked",
  "-language:reflectiveCalls",
  "-language:postfixOps",
  "-language:implicitConversions",
  "-language:higherKinds",
  "-Ypartial-unification"
)

libraryDependencies ++= Dependencies.dependencies
libraryDependencies ++= scalaVersion(sv => Dependencies.scalaReflect(sv)).value
