/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.streampark.flink.client.impl

import org.apache.streampark.common.util.{AssertUtils, Utils}
import org.apache.streampark.flink.client.`trait`.YarnClientTrait
import org.apache.streampark.flink.client.bean._

import org.apache.commons.lang3.StringUtils
import org.apache.flink.api.common.JobID
import org.apache.flink.client.deployment.DefaultClusterClientServiceLoader
import org.apache.flink.client.program.ClusterClient
import org.apache.flink.configuration._
import org.apache.flink.runtime.util.HadoopUtils
import org.apache.flink.yarn.YarnClusterDescriptor
import org.apache.flink.yarn.configuration.{YarnConfigOptions, YarnDeploymentTarget}
import org.apache.hadoop.security.UserGroupInformation
import org.apache.hadoop.yarn.api.records.{ApplicationId, FinalApplicationStatus}
import org.apache.hadoop.yarn.exceptions.ApplicationNotFoundException
import org.apache.hadoop.yarn.util.ConverterUtils

import java.util

import scala.collection.convert.ImplicitConversions._

/** Submit Job to YARN Session Cluster */
object YarnSessionClient extends YarnClientTrait {

  /**
   * @param submitRequest
   * @param flinkConfig
   */
  override def setConfig(submitRequest: SubmitRequest, flinkConfig: Configuration): Unit = {
    super.setConfig(submitRequest, flinkConfig)
    flinkConfig
      .safeSet(DeploymentOptions.TARGET, YarnDeploymentTarget.SESSION.getName)
    logInfo(s"""
               |------------------------------------------------------------------
               |Effective submit configuration: $flinkConfig
               |------------------------------------------------------------------
               |""".stripMargin)
  }

  /**
   * @param deployRequest
   * @param flinkConfig
   */
  def deployClusterConfig(deployRequest: DeployRequest, flinkConfig: Configuration): Unit = {
    val flinkDefaultConfiguration = getFlinkDefaultConfiguration(
      deployRequest.flinkVersion.flinkHome)
    val currentUser = UserGroupInformation.getCurrentUser
    logDebug(s"UserGroupInformation currentUser: $currentUser")
    if (HadoopUtils.isKerberosSecurityEnabled(currentUser)) {
      logDebug(s"kerberos Security is Enabled...")
      val useTicketCache =
        flinkDefaultConfiguration.get(SecurityOptions.KERBEROS_LOGIN_USETICKETCACHE)
      AssertUtils.required(
        HadoopUtils.areKerberosCredentialsValid(currentUser, useTicketCache),
        s"Hadoop security with Kerberos is enabled but the login user $currentUser does not have Kerberos credentials or delegation tokens!")
    }

    val shipFiles = new util.ArrayList[String]()
    shipFiles.add(s"${deployRequest.flinkVersion.flinkHome}/lib")
    shipFiles.add(s"${deployRequest.flinkVersion.flinkHome}/plugins")

    flinkConfig
      // flinkDistJar
      .safeSet(YarnConfigOptions.FLINK_DIST_JAR, deployRequest.hdfsWorkspace.flinkDistJar)
      // flink lib
      .safeSet(YarnConfigOptions.SHIP_FILES, shipFiles)
      // yarnDeployment Target
      .safeSet(DeploymentOptions.TARGET, YarnDeploymentTarget.SESSION.getName)
      // conf dir
      .safeSet(DeploymentOptionsInternal.CONF_DIR, s"${deployRequest.flinkVersion.flinkHome}/conf")

    logInfo(s"""
               |------------------------------------------------------------------
               |Effective submit configuration: $flinkConfig
               |------------------------------------------------------------------
               |""".stripMargin)
  }

  @throws[Exception]
  override def doSubmit(
      submitRequest: SubmitRequest,
      flinkConfig: Configuration): SubmitResponse = {
    val yarnClusterDescriptor = getYarnSessionClusterDescriptor(flinkConfig)
    val clusterDescriptor = yarnClusterDescriptor._2
    val yarnClusterId: ApplicationId = yarnClusterDescriptor._1
    val programJobGraph =
      super.getJobGraph(flinkConfig, submitRequest, submitRequest.userJarFile)
    val packageProgram = programJobGraph._1
    val jobGraph = programJobGraph._2
    val client = clusterDescriptor.retrieve(yarnClusterId).getClusterClient
    val jobId = client.submitJob(jobGraph).get().toString
    val jobManagerUrl = client.getWebInterfaceURL

    logInfo(s"""
               |-------------------------<<applicationId>>------------------------
               |Flink Job Started: jobId: $jobId , applicationId: ${yarnClusterId.toString}
               |__________________________________________________________________
               |""".stripMargin)
    val resp = SubmitResponse(yarnClusterId.toString, flinkConfig.toMap, jobId, jobManagerUrl)
    closeSubmit(submitRequest, packageProgram, client, clusterDescriptor)
    resp
  }

  private[this] def executeClientAction[O, R <: SavepointRequestTrait](
      savepointRequestTrait: R,
      flinkConfig: Configuration,
      actFunc: (JobID, ClusterClient[_]) => O): O = {
    flinkConfig
      .safeSet(YarnConfigOptions.APPLICATION_ID, savepointRequestTrait.clusterId)
      .safeSet(DeploymentOptions.TARGET, YarnDeploymentTarget.SESSION.getName)
    logInfo(s"""
               |------------------------------------------------------------------
               |Effective submit configuration: $flinkConfig
               |------------------------------------------------------------------
               |""".stripMargin)

    var clusterDescriptor: YarnClusterDescriptor = null
    var client: ClusterClient[ApplicationId] = null
    try {
      val yarnClusterDescriptor = getYarnSessionClusterDescriptor(flinkConfig)
      clusterDescriptor = yarnClusterDescriptor._2
      client = clusterDescriptor.retrieve(yarnClusterDescriptor._1).getClusterClient
      actFunc(JobID.fromHexString(savepointRequestTrait.jobId), client)
    } catch {
      case e: Exception =>
        logError(s"${savepointRequestTrait.getClass.getSimpleName} for flink yarn session job fail")
        e.printStackTrace()
        throw e
    } finally {
      Utils.close(client, clusterDescriptor)
    }
  }

  override def doCancel(
      cancelRequest: CancelRequest,
      flinkConfig: Configuration): CancelResponse = {
    executeClientAction(
      cancelRequest,
      flinkConfig,
      (jobID, clusterClient) => {
        val actionResult = super.cancelJob(cancelRequest, jobID, clusterClient)
        CancelResponse(actionResult)
      })
  }

  override def doTriggerSavepoint(
      savepointRequest: TriggerSavepointRequest,
      flinkConfig: Configuration): SavepointResponse = {
    executeClientAction(
      savepointRequest,
      flinkConfig,
      (jobID, clusterClient) => {
        val actionResult =
          super.triggerSavepoint(savepointRequest, jobID, clusterClient)
        SavepointResponse(actionResult)
      })
  }

  def deploy(deployRequest: DeployRequest): DeployResponse = {
    logInfo(
      s"""
         |--------------------------------------- flink yarn sesion start ---------------------------------------
         |    userFlinkHome    : ${deployRequest.flinkVersion.flinkHome}
         |    flinkVersion     : ${deployRequest.flinkVersion.version}
         |    execMode         : ${deployRequest.executionMode.name()}
         |    clusterId        : ${deployRequest.clusterId}
         |    properties       : ${deployRequest.properties.mkString(",")}
         |-------------------------------------------------------------------------------------------------------
         |""".stripMargin)
    var clusterDescriptor: YarnClusterDescriptor = null
    var client: ClusterClient[ApplicationId] = null
    try {
      val flinkConfig =
        extractConfiguration(deployRequest.flinkVersion.flinkHome, deployRequest.properties)
      deployClusterConfig(deployRequest, flinkConfig)
      val yarnClusterDescriptor = getSessionClusterDeployDescriptor(flinkConfig)
      clusterDescriptor = yarnClusterDescriptor._2
      if (StringUtils.isNotBlank(deployRequest.clusterId)) {
        try {
          val applicationStatus =
            clusterDescriptor.getYarnClient
              .getApplicationReport(ApplicationId.fromString(deployRequest.clusterId))
              .getFinalApplicationStatus
          if (FinalApplicationStatus.UNDEFINED == applicationStatus) {
            // application is running
            val yarnClient = clusterDescriptor
              .retrieve(ApplicationId.fromString(deployRequest.clusterId))
              .getClusterClient
            if (yarnClient.getWebInterfaceURL != null) {
              return DeployResponse(yarnClient.getWebInterfaceURL, yarnClient.getClusterId.toString)
            }
          }
        } catch {
          case _: ApplicationNotFoundException =>
            logInfo("this applicationId have not managed by yarn ,need deploy ...")
        }
      }
      val clientProvider =
        clusterDescriptor.deploySessionCluster(yarnClusterDescriptor._1)
      client = clientProvider.getClusterClient
      if (client.getWebInterfaceURL != null) {
        DeployResponse(client.getWebInterfaceURL, client.getClusterId.toString)
      } else {
        null
      }
    } catch {
      case e: Exception =>
        logError(s"start flink session fail in ${deployRequest.executionMode} mode")
        e.printStackTrace()
        throw e
    } finally {
      Utils.close(client, clusterDescriptor)
    }
  }

  def shutdown(shutDownRequest: ShutDownRequest): ShutDownResponse = {
    var clusterDescriptor: YarnClusterDescriptor = null
    var client: ClusterClient[ApplicationId] = null
    try {
      val flinkConfig = getFlinkDefaultConfiguration(shutDownRequest.flinkVersion.flinkHome)
      shutDownRequest.properties.foreach(m =>
        m._2 match {
          case v if v != null => flinkConfig.setString(m._1, m._2.toString)
          case _ =>
        })
      flinkConfig.safeSet(YarnConfigOptions.APPLICATION_ID, shutDownRequest.clusterId)
      flinkConfig.safeSet(DeploymentOptions.TARGET, YarnDeploymentTarget.SESSION.getName)
      val yarnClusterDescriptor = getSessionClusterDescriptor(flinkConfig)
      clusterDescriptor = yarnClusterDescriptor._2
      val shutDownState = FinalApplicationStatus.UNDEFINED.equals(
        clusterDescriptor.getYarnClient
          .getApplicationReport(ApplicationId.fromString(shutDownRequest.clusterId))
          .getFinalApplicationStatus)
      if (shutDownState) {
        val clientProvider =
          clusterDescriptor.retrieve(yarnClusterDescriptor._1)
        client = clientProvider.getClusterClient
        client.shutDownCluster()
      }
      logInfo(s"the ${shutDownRequest.clusterId}'s final status is ${clusterDescriptor.getYarnClient
          .getApplicationReport(ConverterUtils.toApplicationId(shutDownRequest.clusterId))
          .getFinalApplicationStatus}")
      ShutDownResponse(shutDownRequest.clusterId)
    } catch {
      case e: Exception =>
        logError(s"shutdown flink session fail in ${shutDownRequest.executionMode} mode")
        e.printStackTrace()
        throw e
    } finally {
      Utils.close(client, clusterDescriptor)
    }
  }

  private[this] def getYarnSessionClusterDescriptor(
      flinkConfig: Configuration): (ApplicationId, YarnClusterDescriptor) = {
    val serviceLoader = new DefaultClusterClientServiceLoader
    val clientFactory =
      serviceLoader.getClusterClientFactory[ApplicationId](flinkConfig)
    val yarnClusterId: ApplicationId = clientFactory.getClusterId(flinkConfig)
    require(yarnClusterId != null)
    val clusterDescriptor =
      clientFactory
        .createClusterDescriptor(flinkConfig)
        .asInstanceOf[YarnClusterDescriptor]
    (yarnClusterId, clusterDescriptor)
  }

}
