/*
Copyright 2007-2010 Selenium committers

Licensed under the Apache License, Version 2.0 (the "License");
you may not use this file except in compliance with the License.
You may obtain a copy of the License at

     http://www.apache.org/licenses/LICENSE-2.0

Unless required by applicable law or agreed to in writing, software
distributed under the License is distributed on an "AS IS" BASIS,
WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
See the License for the specific language governing permissions and
limitations under the License.
 */
package com.tribbloids.spookystuff.dsl

import com.gargoylesoftware.htmlunit.BrowserVersion
import com.tribbloids.spookystuff.SpookyContext
import com.tribbloids.spookystuff.session.{CleanWebDriver, CleanWebDriverHelper, ProxySetting}
import org.apache.spark.SparkFiles
import org.openqa.selenium.htmlunit.HtmlUnitDriver
import org.openqa.selenium.phantomjs.{PhantomJSDriver, PhantomJSDriverService}
import org.openqa.selenium.remote.CapabilityType._
import org.openqa.selenium.remote.{BrowserType, CapabilityType, DesiredCapabilities}
import org.openqa.selenium.{Capabilities, Platform, Proxy}
import org.slf4j.LoggerFactory

//TODO: switch to DriverPool! Tor cannot handle too many connection request.
sealed abstract class DriverFactory extends Serializable{

  final def newInstance(capabilities: Capabilities, spooky: SpookyContext): CleanWebDriver = {
    val result = _newInstance(capabilities, spooky)

    result
  }

  def _newInstance(capabilities: Capabilities, spooky: SpookyContext): CleanWebDriver
}

object DriverFactories {

  object PhantomJS {

    val remotePhantomJSURL = "https://s3-us-west-1.amazonaws.com/spooky-bin/phantomjs-linux/phantomjs"

    def pathOptionFromEnv = Option(System.getenv("PHANTOMJS_PATH"))
      .orElse(Option(System.getProperty("phantomjs.binary.path")))

    def pathFromSparkMaster(nameFromMaster: String) = Option(nameFromMaster).map(SparkFiles.get).orNull

    def path(nameFromMaster: String): String = pathOptionFromEnv
      .getOrElse{
        LoggerFactory.getLogger(this.getClass).info("$PHANTOMJS_PATH does not exist, downloading from master")
        pathFromSparkMaster(nameFromMaster)
      }

    //only accessable from driver
    @transient def fileName = pathOptionFromEnv.flatMap{
      _.split("/").lastOption
    }.getOrElse{
      remoteFileName
    }
    
    @transient def remoteFileName =
      remotePhantomJSURL.split("/").last
  }

  case class PhantomJS(
                        loadImages: Boolean = false,
                        ignoreExecutableInEnvironment: Boolean = false
                        )
    extends DriverFactory {

    val fileNameFromMaster: String = PhantomJS.fileName

    @transient lazy val exePath = {
      val path = if (!ignoreExecutableInEnvironment) PhantomJS.path(fileNameFromMaster)
      else PhantomJS.pathFromSparkMaster(fileNameFromMaster)

      assert(path != null, "INTERNAL ERROR: PhantomJS has null path")
      path
    }

    @transient lazy val baseCaps = {
      val baseCaps = new DesiredCapabilities(BrowserType.PHANTOMJS, "", Platform.ANY)

      baseCaps.setJavascriptEnabled(true); //< not really needed: JS enabled by default
      baseCaps.setCapability(CapabilityType.SUPPORTS_FINDING_BY_CSS, true)
      //  baseCaps.setCapability(CapabilityType.HAS_NATIVE_EVENTS, false)
      baseCaps.setCapability(TAKES_SCREENSHOT, true)
      baseCaps.setCapability(ACCEPT_SSL_CERTS, true)
      baseCaps.setCapability(SUPPORTS_ALERTS, true)
      baseCaps.setCapability(PhantomJSDriverService.PHANTOMJS_EXECUTABLE_PATH_PROPERTY, exePath)
      baseCaps.setCapability(PhantomJSDriverService.PHANTOMJS_PAGE_SETTINGS_PREFIX + "loadImages", loadImages)
      baseCaps
    }

    //    baseCaps.setCapability(PhantomJSDriverService.PHANTOMJS_PAGE_SETTINGS_PREFIX+"resourceTimeout", Const.resourceTimeout*1000)

    def newCap(capabilities: Capabilities, spooky: SpookyContext): DesiredCapabilities = {
      val result = new DesiredCapabilities(baseCaps)

      result.setCapability(PhantomJSDriverService.PHANTOMJS_PAGE_SETTINGS_PREFIX+"resourceTimeout", spooky.conf.remoteResourceTimeout.toMillis)

      val userAgent = spooky.conf.userAgent
      if (userAgent != null) result.setCapability(PhantomJSDriverService.PHANTOMJS_PAGE_SETTINGS_PREFIX + "userAgent", userAgent)

      val proxy = spooky.conf.proxy()

      if (proxy != null)
        result.setCapability(
          PhantomJSDriverService.PHANTOMJS_CLI_ARGS,
          Array("--proxy=" + proxy.addr+":"+proxy.port, "--proxy-type=" + proxy.protocol)
        )

      result.merge(capabilities)
    }

    //called from executors
    override def _newInstance(capabilities: Capabilities, spooky: SpookyContext): CleanWebDriver = {


      new PhantomJSDriver(newCap(capabilities, spooky)) with CleanWebDriverHelper
    }
  }

  case class HtmlUnit(
                       browser: BrowserVersion = BrowserVersion.getDefault
                       ) extends DriverFactory {

    val baseCaps = new DesiredCapabilities(BrowserType.HTMLUNIT, "", Platform.ANY)

    def newCap(capabilities: Capabilities, spooky: SpookyContext): DesiredCapabilities = {
      val result = new DesiredCapabilities(baseCaps)

      val userAgent = spooky.conf.userAgent
      //TODO: this is useless, need custom BrowserVersion
      //see http://stackoverflow.com/questions/12853715/setting-user-agent-for-htmlunitdriver-selenium
      if (userAgent != null) result.setCapability(PhantomJSDriverService.PHANTOMJS_PAGE_SETTINGS_PREFIX + "userAgent", userAgent)

      val proxy: ProxySetting = spooky.conf.proxy()

      if (proxy != null) {
        result.setCapability(PROXY, proxy.toSeleniumProxy)
      }

      result.merge(capabilities)
    }

    override def _newInstance(capabilities: Capabilities, spooky: SpookyContext): CleanWebDriver = {

      val cap = newCap(capabilities, spooky)
      val driver = new HtmlUnitDriver(browser) with CleanWebDriverHelper
      driver.setJavascriptEnabled(true)
      driver.setProxySettings(Proxy.extractFrom(cap))

      driver
    }
  }

  ////just for debugging
  ////a bug in this driver has caused it unusable in Firefox 32
  //object FirefoxDriverFactory extends DriverFactory {
  //
  //  val baseCaps = new DesiredCapabilities
  //  //  baseCaps.setJavascriptEnabled(true);                //< not really needed: JS enabled by default
  //  //  baseCaps.setCapability(CapabilityType.SUPPORTS_FINDING_BY_CSS,true)
  //
  //  //  val FirefoxRootPath = "/usr/lib/phantomjs/"
  //  //  baseCaps.setCapability("webdriver.firefox.bin", "firefox");
  //  //  baseCaps.setCapability("webdriver.firefox.profile", "WebDriver");
  //
  //  override def newInstance(capabilities: Capabilities, spooky: SpookyContext): WebDriver = {
  //    val newCap = baseCaps.merge(capabilities)
  //
  //    Utils.retry(Const.DFSInPartitionRetry) {
  //      Utils.withDeadline(spooky.distributedResourceTimeout) {new FirefoxDriver(newCap)}
  //    }
  //  }
  //}

}