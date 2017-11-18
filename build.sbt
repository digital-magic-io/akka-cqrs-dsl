name         := "akka-cqrs-dsl"
organization := "io.digital-magic"
version      := "1.4"

organizationHomepage := Some(url("http://www.digital-magic.io"))
startYear            := Some(2016)

scalaVersion       := Dependencies.Versions.scala211
crossScalaVersions := Seq(Dependencies.Versions.scala211, Dependencies.Versions.scala212)

scalacOptions ++= Seq(
  "-feature",
  "-deprecation",
  "-unchecked",
  "-language:reflectiveCalls",
  "-language:postfixOps",
  "-language:implicitConversions"
)

libraryDependencies ++= Dependencies.dependencies
