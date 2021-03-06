/*
 * Copyright © 2015-2018 Santer Reply S.p.A.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package it.reply.orchestrator.service.deployment.providers;

import alien4cloud.model.components.OutputDefinition;
import alien4cloud.model.topology.NodeTemplate;
import alien4cloud.model.topology.RelationshipTemplate;
import alien4cloud.model.topology.Topology;
import alien4cloud.tosca.model.ArchiveRoot;

import com.google.common.collect.MoreCollectors;

import it.reply.orchestrator.annotation.DeploymentProviderQualifier;
import it.reply.orchestrator.config.properties.MarathonProperties;
import it.reply.orchestrator.dal.entity.Deployment;
import it.reply.orchestrator.dal.entity.Resource;
import it.reply.orchestrator.dal.repository.ResourceRepository;
import it.reply.orchestrator.dto.deployment.DeploymentMessage;
import it.reply.orchestrator.dto.mesos.MesosContainer;
import it.reply.orchestrator.dto.mesos.MesosPortMapping;
import it.reply.orchestrator.dto.mesos.marathon.MarathonApp;
import it.reply.orchestrator.enums.DeploymentProvider;
import it.reply.orchestrator.exception.service.DeploymentException;
import it.reply.orchestrator.service.IndigoInputsPreProcessorService;
import it.reply.orchestrator.service.IndigoInputsPreProcessorService.RuntimeProperties;
import it.reply.orchestrator.service.ToscaService;
import it.reply.orchestrator.service.deployment.providers.factory.MarathonClientFactory;
import it.reply.orchestrator.utils.CommonUtils;
import it.reply.orchestrator.utils.ToscaConstants;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Supplier;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import lombok.extern.slf4j.Slf4j;

import mesosphere.marathon.client.Marathon;
import mesosphere.marathon.client.MarathonException;
import mesosphere.marathon.client.model.v2.App;
import mesosphere.marathon.client.model.v2.Container;
import mesosphere.marathon.client.model.v2.Docker;
import mesosphere.marathon.client.model.v2.ExternalVolume;
import mesosphere.marathon.client.model.v2.Group;
import mesosphere.marathon.client.model.v2.HealthCheck;
import mesosphere.marathon.client.model.v2.LocalVolume;
import mesosphere.marathon.client.model.v2.Port;
import mesosphere.marathon.client.model.v2.Volume;

import org.apache.commons.lang3.ObjectUtils;
import org.apache.commons.lang3.StringUtils;
import org.jgrapht.graph.DirectedMultigraph;
import org.jgrapht.traverse.TopologicalOrderIterator;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.util.CollectionUtils;

@Service
@DeploymentProviderQualifier(DeploymentProvider.MARATHON)
@Slf4j
public class MarathonServiceImpl extends AbstractMesosDeploymentService<MarathonApp, App> {

  private static final int MAX_EXTERNAL_VOLUME_NAME_LENGHT = 255; // OST cinder volume name limit

  private static final Pattern APP_NAME_VALIDATOR = Pattern.compile(
      "^(([a-z0-9]|[a-z0-9][a-z0-9\\-]*[a-z0-9])\\.)*"
          + "([a-z0-9]|[a-z0-9][a-z0-9\\-]*[a-z0-9])|(\\.|\\.\\.)$");

  @Autowired
  private ToscaService toscaService;

  @Autowired
  private ResourceRepository resourceRepository;

  @Autowired
  private IndigoInputsPreProcessorService indigoInputsPreProcessorService;

  @Autowired
  private MarathonClientFactory marathonClientFactory;

  protected Marathon getMarathonClient(Deployment deployment) {
    return marathonClientFactory.build(deployment);
  }

  protected Group createGroup(Deployment deployment) {
    ArchiveRoot ar = toscaService
        .prepareTemplate(deployment.getTemplate(), deployment.getParameters());

    Map<String, NodeTemplate> nodes = Optional
        .ofNullable(ar.getTopology())
        .map(Topology::getNodeTemplates)
        .orElseGet(HashMap::new);

    // don't check for cycles, already validated at web-service time
    DirectedMultigraph<NodeTemplate, RelationshipTemplate> graph =
        toscaService.buildNodeGraph(nodes, false);

    TopologicalOrderIterator<NodeTemplate, RelationshipTemplate> orderIterator =
        new TopologicalOrderIterator<>(graph);

    List<NodeTemplate> orderedMarathonApps = CommonUtils
        .iteratorToStream(orderIterator)
        .filter(node -> toscaService.isOfToscaType(node, ToscaConstants.Nodes.MARATHON))
        .collect(Collectors.toList());

    Group group = new Group();
    List<App> apps = new ArrayList<>();
    for (NodeTemplate marathonNode : orderedMarathonApps) {

      MarathonApp marathonTask = buildTask(graph, marathonNode, marathonNode.getName());
      List<Resource> resources = resourceRepository
          .findByToscaNodeNameAndDeployment_id(marathonNode.getName(), deployment.getId());

      resources.forEach(resource -> resource.setIaasId(marathonTask.getId()));
      marathonTask.setInstances(resources.size());

      App marathonApp = generateExternalTaskRepresentation(marathonTask);
      apps.add(marathonApp);
    }
    group.setApps(apps);
    return group;
  }

  @Override
  public boolean doDeploy(DeploymentMessage deploymentMessage) {
    Deployment deployment = getDeployment(deploymentMessage);

    Group group = createGroup(deployment);

    String groupId = deployment.getId();

    MarathonProperties marathonProperties =
        marathonClientFactory.getFrameworkProperties(deployment);
    CommonUtils
        .nullableCollectionToStream(group.getApps())
        .filter(Objects::nonNull)
        .map(app -> app.getContainer())
        .filter(Objects::nonNull)
        .flatMap(container -> CommonUtils.nullableCollectionToStream(container.getVolumes()))
        .forEach(volume -> {
          if (volume instanceof ExternalVolume) {
            // prepend group id to volume name
            ExternalVolume externalVolume = (ExternalVolume) volume;
            String baseVolumeName = groupId + "-";
            String nameWithGroupId = baseVolumeName + externalVolume.getName();
            if (nameWithGroupId.length() > MAX_EXTERNAL_VOLUME_NAME_LENGHT) {
              throw new IllegalArgumentException(String.format(
                  "Volume name %s is too long. Only names less than %s chars are allowed",
                  externalVolume.getName(),
                  MAX_EXTERNAL_VOLUME_NAME_LENGHT - baseVolumeName.length()));
            }
            externalVolume.setName(nameWithGroupId);
          } else if (volume instanceof LocalVolume) {
            // set as /basePath/groupId
            ((LocalVolume) volume)
                .setHostPath(marathonProperties.generateLocalVolumesHostPath(groupId));
          }
        });

    deployment.setEndpoint(groupId);
    group.setId(groupId);

    LOG.info("Creating Marathon App Group for deployment {} with definition:\n{}",
        deployment.getId(), group);
    getMarathonClient(deployment).createGroup(group);
    return true;

  }

  @Override
  public boolean isDeployed(DeploymentMessage deploymentMessage) throws DeploymentException {
    Deployment deployment = getDeployment(deploymentMessage);
    String groupId = deployment.getId();

    Group group = getPolulatedGroup(deployment, groupId);
    ///////////////////////////////////////////////////////////////
    Collection<App> apps = Optional.ofNullable(group.getApps()).orElseGet(ArrayList::new);
    LOG.debug("Marathon App Group for deployment {} current status:\n{}", deployment.getId(),
        group);

    // if no Mesos deployments are left -> deploy is done
    boolean isDeployed = apps.stream().allMatch(app -> isAppDeployed(app));
    LOG.debug("Marathon App Group for deployment {} is deployed? {}", deployment.getId(),
        isDeployed);
    return isDeployed;
  }

  @Deprecated
  // TODO remove it and use just getGroup with embed params (requires marathon client version >
  // 6.0.0)
  private Group getPolulatedGroup(Deployment deployment, String groupId) throws MarathonException {
    final Marathon client = getMarathonClient(deployment);
    Group group = client.getGroup(groupId);

    Collection<App> apps = Optional.ofNullable(group.getApps()).orElseGet(Collections::emptyList);

    List<App> completeInfoApps = new ArrayList<>();
    for (App app : apps) {
      completeInfoApps.add(client.getApp(app.getId()).getApp());
    }
    apps = completeInfoApps;

    group.setApps(apps);
    return group;
  }

  private boolean isAppDeployed(App app) {
    boolean noDeploymentsLeft = CollectionUtils.isEmpty(app.getDeployments());
    boolean allTasksReady;
    List<HealthCheck> healtchecks =
        Optional.ofNullable(app.getHealthChecks()).orElseGet(Collections::emptyList);
    if (healtchecks.isEmpty()) {
      // if no health-checks are defined -> we're good if they are running
      allTasksReady = ObjectUtils.compare(app.getInstances(), app.getTasksRunning(), false) <= 0;
    } else {
      allTasksReady = ObjectUtils.compare(app.getInstances(), app.getTasksHealthy(), false) <= 0;
    }

    return noDeploymentsLeft && allTasksReady;
  }

  @Override
  public boolean doUpdate(DeploymentMessage deploymentMessage, String template) {
    throw new UnsupportedOperationException("Marathon app deployments do not support update.");
  }

  @Override
  public boolean doUndeploy(DeploymentMessage deploymentMessage) {
    Deployment deployment = getDeployment(deploymentMessage);

    String groupId = deployment.getId();

    try {
      getMarathonClient(deployment).deleteGroup(groupId, true);
    } catch (MarathonException ex) {
      if (HttpStatus.NOT_FOUND.value() != ex.getStatus()) {
        throw ex;
      }
    }
    return true;
  }

  @Override
  public boolean isUndeployed(DeploymentMessage deploymentMessage) throws DeploymentException {
    boolean isUndeployed = false;
    Deployment deployment = getDeployment(deploymentMessage);
    String groupId = deployment.getId();
    try {
      getMarathonClient(deployment).getGroup(groupId);
    } catch (MarathonException ex) {
      if (HttpStatus.NOT_FOUND.value() == ex.getStatus()) {
        isUndeployed = true;
      } else {
        throw ex;
      }
    }
    LOG.debug("Marathon App Group for deployment {} is undeployed? {}", deployment.getId(),
        isUndeployed);
    return isUndeployed;

  }

  @Override
  protected MarathonApp createInternalTaskRepresentation() {
    return new MarathonApp();
  }

  @Override
  protected App generateExternalTaskRepresentation(MarathonApp marathonTask) {
    App app = new App();
    String id = marathonTask.getId();
    if (!APP_NAME_VALIDATOR.matcher(id).matches()) {
      throw new IllegalArgumentException(String.format(
          "Illegal name for TOSCA node <%s>: "
              + "name for nodes of type %s must fully match regular expression %s",
          id, marathonTask.getToscaNodeName(), APP_NAME_VALIDATOR.pattern()));
    }
    app.setId(id);
    app.setCmd(marathonTask.getCmd());
    app.setConstraints(marathonTask.getConstraints());
    app.setCpus(marathonTask.getCpus());
    app.setMem(marathonTask.getMemSize());
    app.setUris(marathonTask.getUris());
    app.setLabels(marathonTask.getLabels());
    app.setEnv(new HashMap<>(marathonTask.getEnv()));
    app.setInstances(marathonTask.getInstances());
    marathonTask
        .getContainer()
        .ifPresent(mesosContainer -> app.setContainer(generateContainer(mesosContainer)));
    return app;
  }

  private Container generateContainer(MesosContainer mesosContainer) {
    Container container = new Container();
    if (mesosContainer.getType() == MesosContainer.Type.DOCKER) {
      container.setType(MesosContainer.Type.DOCKER.getName());
      container.setVolumes(mesosContainer
          .getVolumes()
          .stream()
          .map(this::generateVolume)
          .collect(Collectors.toList()));
      Docker docker = new Docker();
      container.setDocker(docker);
      docker.setImage(mesosContainer.getImage());
      List<Port> ports = mesosContainer
          .getPortMappings()
          .stream()
          .map(this::generatePort)
          .collect(Collectors.toList());
      docker.setPortMappings(ports);

      docker.setForcePullImage(mesosContainer.isForcePullImage());
      docker.setPrivileged(mesosContainer.isPriviliged());

      //// HARDCODED BITS //////
      docker.setNetwork("BRIDGE");
      //////////////////////////
    } else {
      throw new DeploymentException(
          "Unknown Mesos container type: " + mesosContainer.getType().toString());
    }
    return container;
  }

  private Port generatePort(MesosPortMapping portMapping) {
    Port port = new Port();
    port.setContainerPort(portMapping.getContainerPort());
    port.setProtocol(portMapping.getProtocol().getName());
    port.setServicePort(portMapping.getServicePort());
    return port;
  }

  Volume generateVolume(String containerVolumeMount) {

    Supplier<DeploymentException> validationExceptionSupplier = () -> new DeploymentException(String
        .format("Volume mount <%s> not supported for marathon containers", containerVolumeMount));

    if (!containerVolumeMount.matches("([^:]+):([^:]+)(?::([^:]+):([^:]+):([^:]+))?")) {
      throw validationExceptionSupplier.get();
    }

    // split the volumeMount string and extract only the non blank strings
    List<String> volumeMountSegments = Arrays
        .asList(containerVolumeMount.split(":"))
        .stream()
        .sequential()
        .filter(StringUtils::isNotBlank)
        .collect(Collectors.toList());

    switch (volumeMountSegments.size()) {
      case 2:
        LocalVolume localVolume = new LocalVolume();
        localVolume.setContainerPath(volumeMountSegments.get(0));
        localVolume.setMode(volumeMountSegments.get(1).toUpperCase(Locale.US));
        return localVolume;
      case 5:
        ExternalVolume externalVolume = new ExternalVolume();
        externalVolume.setName(volumeMountSegments.get(0));
        externalVolume.setContainerPath(volumeMountSegments.get(1));
        externalVolume.setMode(volumeMountSegments.get(2).toUpperCase(Locale.US));
        externalVolume.setProvider(volumeMountSegments.get(3));
        externalVolume.setDriver(volumeMountSegments.get(4));
        return externalVolume;
      default:
        throw validationExceptionSupplier.get();
    }
  }

  @Override
  public Optional<String> getAdditionalErrorInfoInternal(DeploymentMessage deploymentMessage) {
    Deployment deployment = getDeployment(deploymentMessage);
    String groupId = deployment.getId();

    Group group = getPolulatedGroup(deployment, groupId);

    Stream<App> failedApps = Optional
        .ofNullable(group.getApps())
        .orElseGet(Collections::emptyList)
        .stream()
        .filter(app -> !this.isAppDeployed(app));

    String failedAppsMessage = failedApps
        .map(App::getLastTaskFailure)
        .filter(Objects::nonNull)
        .map(Objects::toString)
        .collect(Collectors.joining("\n"));

    if (failedAppsMessage.isEmpty()) {
      return Optional.empty();
    } else {
      return Optional.of("Some Application failed:\n" + failedAppsMessage);
    }
  }

  @Override
  public void finalizeDeploy(DeploymentMessage deploymentMessage) {
    Deployment deployment = getDeployment(deploymentMessage);

    ArchiveRoot ar = toscaService
        .prepareTemplate(deployment.getTemplate(), deployment.getParameters());

    Map<String, OutputDefinition> outputs = Optional
        .ofNullable(ar.getTopology())
        .map(Topology::getOutputs)
        .orElseGet(HashMap::new);
    if (!outputs.isEmpty()) {
      String groupId = deployment.getId();
      Group group = getMarathonClient(deployment).getGroup(groupId);

      Map<String, NodeTemplate> nodes = Optional
          .ofNullable(ar.getTopology())
          .map(Topology::getNodeTemplates)
          .orElseGet(HashMap::new);

      DirectedMultigraph<NodeTemplate, RelationshipTemplate> graph =
          toscaService.buildNodeGraph(nodes, false);

      TopologicalOrderIterator<NodeTemplate, RelationshipTemplate> orderIterator =
          new TopologicalOrderIterator<>(graph);

      List<NodeTemplate> orderedMarathonApps = CommonUtils
          .iteratorToStream(orderIterator)
          .filter(node -> toscaService.isOfToscaType(node, ToscaConstants.Nodes.MARATHON))
          .collect(Collectors.toList());

      MarathonProperties marathonProperties =
          marathonClientFactory.getFrameworkProperties(deployment);
      RuntimeProperties runtimeProperties = new RuntimeProperties();
      for (NodeTemplate marathonNode : orderedMarathonApps) {

        runtimeProperties.put(marathonProperties.getLoadBalancerIps(), marathonNode.getName(),
            "load_balancer_ips");

        List<Integer> ports = group
            .getApps()
            .stream()
            .filter(
                app -> app.getId().endsWith("/" + marathonNode.getName()))
            .collect(MoreCollectors.toOptional())
            .map(App::getPorts)
            .orElseGet(ArrayList::new);

        NodeTemplate hostNode = getHostNode(graph, marathonNode);
        for (int i = 0; i < ports.size(); ++i) {
          runtimeProperties.put(ports.get(i), hostNode.getName(), "host",
              "publish_ports", String.valueOf(i), "target");
        }
      }
      deployment.setOutputs(indigoInputsPreProcessorService.processOutputs(ar,
          deployment.getParameters(), runtimeProperties));
    }
    super.finalizeDeploy(deploymentMessage);
  }

}
