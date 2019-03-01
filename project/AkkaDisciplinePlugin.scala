/*
 * Copyright (C) 2019 Lightbend Inc. <https://www.lightbend.com>
 */

package akka

import sbt._
import Keys._
import sbt.plugins.JvmPlugin

/**
  * Initial tests found:
  * `akka-actor` 151 errors with `-Xfatal-warnings`, 6 without the flag
  */
object AkkaDisciplinePlugin extends AutoPlugin with ScalafixSupport {

  import scoverage.ScoverageKeys._
  import scalafix.sbt.ScalafixPlugin

  /** The nightly CI Scoverage job sets `-Dakka.coverage.job=true`. */
  lazy val coverageJobEnabled: Boolean =
    sys.props.getOrElse("akka.coverage.job", "false").toBoolean

  override def trigger: PluginTrigger = allRequirements
  override def requires: Plugins = JvmPlugin && ScalafixPlugin
  override lazy val projectSettings = if (coverageJobEnabled) scoverageSettings else disciplineSettings

  /** Declarative exclusion by specific modules to disable Scoverage:
    * - planned removals in 2.6 https://github.com/akka/akka/milestone/119
    * - docs, protobuf, benchJmh
    */
  lazy val coverageExclude =
    if (coverageJobEnabled) Seq(
      test in Test := {},
      coverageEnabled := false)
    else Nil

  lazy val scalaFixSettings = Seq(
    Compile / scalacOptions += "-Yrangepos")

  private val coverageExcluding = settingKey[Boolean]("")
  private val excludedDir = settingKey[Boolean]("Names of tests to be excluded. Not supported by MultiJVM tests. Example usage: -Dakka.test.names.exclude=TimingSpec")
  private val excludedWildcard = settingKey[Boolean]("Tags of tests to be excluded. It will not be used if you specify -Dakka.test.tags.only. Example usage: -Dakka.test.tags.exclude=long-running")
  private val coverageCheck = settingKey[Unit]("")


  /**
    * Temporarily exclude specific modules for Scoverage by passing a comma-separated
    * list of module names, minus "akka-", using
    * `-Dakka.coverage.exclude=osgi,slf4j` or wildcard
    * `-Dakka.coverage.exclude=slf4j,stream*,persistence*`.
    */
  lazy val coverageJobExclusions =
    Seq("akka") ++ TestExtras.Filter.systemPropertyAsSeq("akka.coverage.excludes").map(v => s"akka-$v")
    
  lazy val coverageJobExclusionsWildcards = coverageJobExclusions.collect { case v if v.endsWith("*") => v.replace("*", "") }

  lazy val scoverageSettings = Seq(
    coverageMinimum := 70,
    coverageFailOnMinimum := false,
    coverageOutputHTML := true,
    coverageHighlighting := {
      import sbt.librarymanagement.{ SemanticSelector, VersionNumber }
      !VersionNumber(scalaVersion.value).matchesSemVer(SemanticSelector("<=2.11.1"))
    }) ++ {
    if (coverageJobEnabled) Seq(
      javaOptions in Test ++= Seq("-XX:+UseG1GC", "-Xmx4G", "-Xms4G"),
      logLevel in ThisBuild := Level.Error,

      excludedDir := coverageJobExclusions.contains(baseDirectory.value.getName),
      excludedWildcard := coverageJobExclusionsWildcards.exists(baseDirectory.value.getName.contains),
      coverageExcluding := {
        val explicit = excludedDir.value
        val wildcard = excludedWildcard.value
        explicit || wildcard
      },
      coverageEnabled := !coverageExcluding.value,
      coverageCheck := {
        if (coverageEnabled.value) println(s"  Coverage Enabled for [${baseDirectory.value.getName}]")
      },
      Test / testOptions := {
        val filters = if (coverageEnabled.value) Nil else Seq(Tests.Filter(s => s.endsWith("NOTONCOVERAGE")))
        println(s"  Coverage Test / testOptions [$filters] for [${baseDirectory.value.getName}]")
        filters
      }
    )
    else Nil
  }

  lazy val disciplineSettings =
    scalaFixSettings ++
      scoverageSettings ++ Seq(
      Compile / scalacOptions ++= disciplineScalacOptions,
      Compile / scalacOptions --= undisciplineScalacOptions,
      Compile / console / scalacOptions --= Seq("-deprecation", "-Xfatal-warnings", "-Xlint", "-Ywarn-unused:imports"),
      Compile / scalacOptions --= (CrossVersion.partialVersion(scalaVersion.value) match {
        case Some((2, 13)) =>
          Seq("-Ywarn-inaccessible", "-Ywarn-infer-any", "-Ywarn-nullary-override", "-Ywarn-nullary-unit", "-Ypartial-unification", "-Yno-adapted-args")
        case Some((2, 12)) =>
          Nil
        case Some((2, 11)) =>
          Seq("-Ywarn-extra-implicit", "-Ywarn-unused:_")
        case _             =>
          Nil
      }))

  /**
    * Remain visibly filtered for future code quality work and removing.
    */
  val undisciplineScalacOptions = Seq(
    "-Ywarn-value-discard",
    "-Ywarn-numeric-widen",
    "-Yno-adapted-args",
    "-Xfatal-warnings")

  /** Optimistic, this is desired over time.
    * -Xlint and -Ywarn-unused: are included in commonScalaOptions.
    * If eventually all modules use this plugin, we could migrate them here.
    */
  val disciplineScalacOptions = Seq(
    // start: must currently remove, version regardless
    "-Xfatal-warnings",
    "-Ywarn-value-discard",
    "-Yno-adapted-args",
    "-Ywarn-numeric-widen",
    // end
    "-Xfuture",
    "-Ywarn-dead-code",
    "-Ywarn-inaccessible",
    "-Ywarn-infer-any",
    "-Ywarn-nullary-override",
    "-Ywarn-nullary-unit",
    "-Ywarn-unused:_",
    "-Ypartial-unification",
    "-Ywarn-extra-implicit",
    "-Ywarn-numeric-widen")

}
