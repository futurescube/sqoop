/**
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.sqoop.handler;

import java.io.IOException;
import java.util.List;
import java.util.Locale;

import org.apache.log4j.Logger;
import org.apache.sqoop.audit.AuditLoggerManager;
import org.apache.sqoop.common.Direction;
import org.apache.sqoop.common.SqoopException;
import org.apache.sqoop.connector.ConnectorManager;
import org.apache.sqoop.connector.spi.SqoopConnector;
import org.apache.sqoop.driver.Driver;
import org.apache.sqoop.json.JobBean;
import org.apache.sqoop.json.JsonBean;
import org.apache.sqoop.json.ValidationResultBean;
import org.apache.sqoop.model.ConfigUtils;
import org.apache.sqoop.model.MDriverConfig;
import org.apache.sqoop.model.MFromConfig;
import org.apache.sqoop.model.MJob;
import org.apache.sqoop.model.MToConfig;
import org.apache.sqoop.repository.Repository;
import org.apache.sqoop.repository.RepositoryManager;
import org.apache.sqoop.server.RequestContext;
import org.apache.sqoop.server.RequestHandler;
import org.apache.sqoop.server.common.ServerError;
import org.apache.sqoop.utils.ClassUtils;
import org.apache.sqoop.validation.ConfigValidationResult;
import org.apache.sqoop.validation.ConfigValidationRunner;
import org.apache.sqoop.validation.Status;
import org.json.simple.JSONObject;
import org.json.simple.JSONValue;

/**
 * Job request handler is supporting following resources:
 *
 * GET /v1/job/:jid
 * Return details about one particular job with id :jid or about all of
 * them if :jid equals to "all".
 *
 * POST /v1/job
 * Create new job
 *
 * PUT /v1/job/:jid
 * Update job with id :jid.
 *
 * PUT /v1/job/:jid/enable
 * Enable job with id :jid
 *
 * PUT /v1/job/:jid/disable
 * Disable job with id :jid
 *
 * DELETE /v1/job/:jid
 * Remove job with id :jid
 *
 * Planned resources:
 *
 * GET /v1/job
 * Get brief list of all jobs present in the system. This resource is not yet
 * implemented.
 */
public class JobRequestHandler implements RequestHandler {

  private static final Logger LOG =
      Logger.getLogger(JobRequestHandler.class);

  private static final String ENABLE = "enable";
  private static final String DISABLE = "disable";

  public JobRequestHandler() {
    LOG.info("JobRequestHandler initialized");
  }

  @Override
  public JsonBean handleEvent(RequestContext ctx) {
    switch (ctx.getMethod()) {
      case GET:
        return getJobs(ctx);
      case POST:
          return createUpdateJob(ctx, false);
      case PUT:
        if (ctx.getLastURLElement().equals(ENABLE)) {
          return enableJob(ctx, true);
        } else if (ctx.getLastURLElement().equals(DISABLE)) {
          return enableJob(ctx, false);
        } else {
          return createUpdateJob(ctx, true);
        }
      case DELETE:
        return deleteJob(ctx);
    }

    return null;
  }

  /**
   * Delete job from  repository.
   *
   * @param ctx Context object
   * @return Empty bean
   */
  private JsonBean deleteJob(RequestContext ctx) {
    String sxid = ctx.getLastURLElement();
    long jid = Long.valueOf(sxid);

    AuditLoggerManager.getInstance()
        .logAuditEvent(ctx.getUserName(), ctx.getRequest().getRemoteAddr(),
        "delete", "job", sxid);

    Repository repository = RepositoryManager.getInstance().getRepository();
    repository.deleteJob(jid);

    return JsonBean.EMPTY_BEAN;
  }

  /**
   * Update or create job in repository.
   *
   * @param ctx Context object
   * @return Validation bean object
   */
  private JsonBean createUpdateJob(RequestContext ctx, boolean update) {

    String username = ctx.getUserName();
    JobBean bean = new JobBean();

    try {
      JSONObject json =
        (JSONObject) JSONValue.parse(ctx.getRequest().getReader());
      bean.restore(json);
    } catch (IOException e) {
      throw new SqoopException(ServerError.SERVER_0003,
        "Can't read request content", e);
    }

    // Get job object
    List<MJob> jobs = bean.getJobs();

    if(jobs.size() != 1) {
      throw new SqoopException(ServerError.SERVER_0003,
        "Expected one job but got " + jobs.size());
    }

    // Job object
    MJob job = jobs.get(0);

    // Verify that user is not trying to spoof us
    MFromConfig fromConfig = ConnectorManager.getInstance()
        .getConnectorConfigurable(job.getConnectorId(Direction.FROM))
        .getFromConfig();
    MToConfig toConfig = ConnectorManager.getInstance()
        .getConnectorConfigurable(job.getConnectorId(Direction.TO))
        .getToConfig();
    MDriverConfig driverConfig = Driver.getInstance().getDriver().getDriverConfig();

    if(!fromConfig.equals(job.getJobConfig(Direction.FROM))
      || !driverConfig.equals(job.getDriverConfig())
      || !toConfig.equals(job.getJobConfig(Direction.TO))) {
      throw new SqoopException(ServerError.SERVER_0003,
        "Detected incorrect config structure");
    }

    // Corresponding connectors for this
    SqoopConnector fromConnector = ConnectorManager.getInstance().getSqoopConnector(job.getConnectorId(Direction.FROM));
    SqoopConnector toConnector = ConnectorManager.getInstance().getSqoopConnector(job.getConnectorId(Direction.TO));

    if (!fromConnector.getSupportedDirections().contains(Direction.FROM)) {
      throw new SqoopException(ServerError.SERVER_0004, "Connector " + fromConnector.getClass().getCanonicalName()
          + " does not support FROM direction.");
    }

    if (!toConnector.getSupportedDirections().contains(Direction.TO)) {
      throw new SqoopException(ServerError.SERVER_0004, "Connector " + toConnector.getClass().getCanonicalName()
          + " does not support TO direction.");
    }

    // Validate user supplied data
    ConfigValidationResult fromConfigValidator = ConfigUtils.validateConfigs(
      job.getJobConfig(Direction.FROM).getConfigs(),
      fromConnector.getJobConfigurationClass(Direction.FROM)
    );
    ConfigValidationResult toConfigValidator = ConfigUtils.validateConfigs(
      job.getJobConfig(Direction.TO).getConfigs(),
      toConnector.getJobConfigurationClass(Direction.TO)
    );
    ConfigValidationResult driverConfigValidator = ConfigUtils.validateConfigs(
      job.getDriverConfig().getConfigs(),
      Driver.getInstance().getDriverJobConfigurationClass()
    );

    Status finalStatus = Status.getWorstStatus(fromConfigValidator.getStatus(), toConfigValidator.getStatus(), driverConfigValidator.getStatus());

    // Return back validations in all cases
    ValidationResultBean validationResultBean = new ValidationResultBean(fromConfigValidator, toConfigValidator);

    // If we're good enough let's perform the action
    if(finalStatus.canProceed()) {
      if(update) {
        AuditLoggerManager.getInstance()
            .logAuditEvent(ctx.getUserName(), ctx.getRequest().getRemoteAddr(),
            "update", "job", String.valueOf(job.getPersistenceId()));

        job.setLastUpdateUser(username);
        RepositoryManager.getInstance().getRepository().updateJob(job);
      } else {
        job.setCreationUser(username);
        job.setLastUpdateUser(username);
        RepositoryManager.getInstance().getRepository().createJob(job);
        validationResultBean.setId(job.getPersistenceId());

        AuditLoggerManager.getInstance()
            .logAuditEvent(ctx.getUserName(), ctx.getRequest().getRemoteAddr(),
            "create", "job", String.valueOf(job.getPersistenceId()));
      }

    }

    return validationResultBean;
  }

  private JsonBean getJobs(RequestContext ctx) {
    String sjid = ctx.getLastURLElement();
    JobBean bean;

    AuditLoggerManager.getInstance()
        .logAuditEvent(ctx.getUserName(), ctx.getRequest().getRemoteAddr(),
        "get", "job", sjid);

    Locale locale = ctx.getAcceptLanguageHeader();
    Repository repository = RepositoryManager.getInstance().getRepository();

    if (sjid.equals(JsonBean.ALL)) {

      List<MJob> jobs = repository.findJobs();
      bean = new JobBean(jobs);

      // Add associated resources into the bean
      for( MJob job : jobs) {
        long fromConnectorId = job.getConnectorId(Direction.FROM);
        long toConnectorId = job.getConnectorId(Direction.TO);
        if(!bean.hasConnectorConfigBundle(fromConnectorId)) {
          bean.addConnectorConfigBundle(fromConnectorId,
            ConnectorManager.getInstance().getResourceBundle(fromConnectorId, locale));
        }
        if(!bean.hasConnectorConfigBundle(toConnectorId)) {
          bean.addConnectorConfigBundle(toConnectorId,
              ConnectorManager.getInstance().getResourceBundle(toConnectorId, locale));
        }
      }
    } else {
      long jid = Long.valueOf(sjid);

      MJob job = repository.findJob(jid);
      long fromConnectorId = job.getConnectorId(Direction.FROM);
      long toConnectorId = job.getConnectorId(Direction.TO);
      bean = new JobBean(job);
      if(!bean.hasConnectorConfigBundle(fromConnectorId)) {
        bean.addConnectorConfigBundle(fromConnectorId,
            ConnectorManager.getInstance().getResourceBundle(fromConnectorId, locale));
      }
      if(!bean.hasConnectorConfigBundle(toConnectorId)) {
        bean.addConnectorConfigBundle(toConnectorId,
            ConnectorManager.getInstance().getResourceBundle(toConnectorId, locale));
      }
    }

    // set driver config bundle
    bean.setDriverConfigBundle(Driver.getInstance().getBundle(locale));
    return bean;
  }

  private JsonBean enableJob(RequestContext ctx, boolean enabled) {
    String[] elements = ctx.getUrlElements();
    String sjid = elements[elements.length - 2];
    long xid = Long.valueOf(sjid);

    Repository repository = RepositoryManager.getInstance().getRepository();
    repository.enableJob(xid, enabled);
    return JsonBean.EMPTY_BEAN;
  }
}
