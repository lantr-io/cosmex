val scala3Version = "3.3.6"
val scalusVersion = "0.11.0"
ThisBuild / scalaVersion := scala3Version
autoCompilerPlugins := true

resolvers +=
    "Sonatype OSS Snapshots" at "https://s01.oss.sonatype.org/content/repositories/snapshots"

name := "scalus"

version := "0.1.0-SNAPSHOT"

scalaVersion := scala3Version

addCompilerPlugin("org.scalus" %% "scalus-plugin" % scalusVersion)

scalacOptions += "-Wunused:all"
scalacOptions += "-deprecation"
scalacOptions += "-feature"

libraryDependencies += "org.scalus" %% "scalus" % scalusVersion
libraryDependencies += "org.scalus" %% "scalus-bloxbean-cardano-client-lib" % scalusVersion
libraryDependencies += "org.scalus" %% "scalus-testkit" % scalusVersion % Test
libraryDependencies += "com.bloxbean.cardano" % "cardano-client-lib" % "0.6.6"

libraryDependencies += "org.scalatest" %% "scalatest" % "3.2.19" % "test"
libraryDependencies += "org.scalatestplus" %% "scalacheck-1-18" % "3.2.19.0" % "test"
