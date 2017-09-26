/*
 * Copyright © 2015-2017 Santer Reply S.p.A.
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

package it.reply.orchestrator.service.deployment.providers.factory;

import com.google.common.collect.Lists;

import alien4cloud.tosca.parser.ParsingException;

import es.upv.i3m.grycap.im.InfrastructureManager;

import it.reply.orchestrator.config.properties.ImProperties;
import it.reply.orchestrator.config.properties.OidcProperties;
import it.reply.orchestrator.dto.CloudProviderEndpoint;
import it.reply.orchestrator.dto.CloudProviderEndpoint.IaaSType;
import it.reply.orchestrator.exception.service.DeploymentException;
import it.reply.orchestrator.utils.CommonUtils;

import org.assertj.core.api.Assertions;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.InjectMocks;
import org.mockito.Spy;
import org.mockito.junit.MockitoJUnit;
import org.mockito.junit.MockitoRule;

import java.net.URI;
import java.util.List;
import java.util.UUID;

import junitparams.JUnitParamsRunner;
import junitparams.Parameters;
import junitparams.converters.Nullable;

@RunWith(JUnitParamsRunner.class)
public class ImClientFactoryTest {

  @Rule
  public MockitoRule rule = MockitoJUnit.rule();

  private static String paasImUrl = "https://im.url";

  private static String iamToken = "J1qK1c18UUGJFAzz9xnH56584l4";

  private static String imTokenAuthHeader =
      "id = im ; type = InfrastructureManager ; token = " + iamToken;

  @InjectMocks
  private ImClientFactory imClientFactory;

  @Spy
  private ImProperties imProperties;

  @Spy
  private OidcProperties oidcProperties;

  @Before
  public void setup() throws ParsingException {
    imProperties.setUrl(CommonUtils.checkNotNull(URI.create(paasImUrl)));
  }

  @Test
  @Parameters({ "custom_id", "null" })
  public void testGetClientOst(@Nullable String headerId) {
    CloudProviderEndpoint cloudProviderEndpoint = new CloudProviderEndpoint();
    cloudProviderEndpoint.setIaasType(IaaSType.OPENSTACK);
    cloudProviderEndpoint.setCpEndpoint("https://host:5000/v3");
    cloudProviderEndpoint.setCpComputeServiceId(UUID.randomUUID().toString());
    cloudProviderEndpoint.setIaasHeaderId(headerId);

    String iaasAuthHeader =
        "id = " + (headerId != null ? headerId : "ost")
            + " ; type = OpenStack ; tenant = oidc ; username = indigo-dc ; password = "
            + iamToken
            + " ; host = https://host:5000 ; auth_version = 3.x_oidc_access_token";
    testGetClient(cloudProviderEndpoint, ImClientFactoryTest.paasImUrl, iaasAuthHeader);
  }

  @Test
  @Parameters({ "custom_id, https://local.im",
      "custom_id, null",
      "null, https://local.im",
      "null, null" })
  public void testGetClientOne(@Nullable String headerId, @Nullable String localImEndpoint) {
    CloudProviderEndpoint cloudProviderEndpoint = new CloudProviderEndpoint();
    cloudProviderEndpoint.setIaasType(IaaSType.OPENNEBULA);
    cloudProviderEndpoint.setCpEndpoint("https://host");
    cloudProviderEndpoint.setCpComputeServiceId(UUID.randomUUID().toString());
    cloudProviderEndpoint.setIaasHeaderId(headerId);
    cloudProviderEndpoint.setImEndpoint(localImEndpoint);

    String iaasAuthHeader =
        "id = " + (headerId != null ? headerId : "one")
            + " ; type = OpenNebula ; host = https://host ; token = " + iamToken;
    testGetClient(cloudProviderEndpoint, (localImEndpoint != null ? localImEndpoint : paasImUrl),
        iaasAuthHeader);
  }

  @Test
  public void testMultipleOneWithLocalIm() {
    CloudProviderEndpoint cloudProviderEndpoint1 = new CloudProviderEndpoint();
    cloudProviderEndpoint1.setIaasType(IaaSType.OPENNEBULA);
    cloudProviderEndpoint1.setCpEndpoint("https://host1");
    cloudProviderEndpoint1.setCpComputeServiceId(UUID.randomUUID().toString());
    cloudProviderEndpoint1.setIaasHeaderId("one1");
    cloudProviderEndpoint1.setImEndpoint("https://local.im1");

    CloudProviderEndpoint cloudProviderEndpoint2 = new CloudProviderEndpoint();
    cloudProviderEndpoint2.setIaasType(IaaSType.OPENNEBULA);
    cloudProviderEndpoint2.setCpEndpoint("https://host2");
    cloudProviderEndpoint2.setCpComputeServiceId(UUID.randomUUID().toString());
    cloudProviderEndpoint2.setIaasHeaderId("one2");
    cloudProviderEndpoint2.setImEndpoint("https://local.im2");

    String iaasAuthHeader =
        "id = one1 ; type = OpenNebula ; host = https://host1 ; token = " + iamToken +
            "\\nid = one2 ; type = OpenNebula ; host = https://host2 ; token = " + iamToken;
    testGetClient(Lists.newArrayList(cloudProviderEndpoint1, cloudProviderEndpoint2), paasImUrl,
        iaasAuthHeader);
  }

  @Test
  @Parameters({ "custom_id", "null" })
  public void testGetClientAws(@Nullable String headerId) {
    CloudProviderEndpoint cloudProviderEndpoint = new CloudProviderEndpoint();
    cloudProviderEndpoint.setIaasType(IaaSType.AWS);
    cloudProviderEndpoint.setUsername("username");
    cloudProviderEndpoint.setPassword("password");
    cloudProviderEndpoint.setCpEndpoint("https://host/");
    cloudProviderEndpoint.setCpComputeServiceId(UUID.randomUUID().toString());
    cloudProviderEndpoint.setIaasHeaderId(headerId);

    String iaasAuthHeader =
        "id = " + (headerId != null ? headerId : "ec2")
            + " ; type = EC2 ; username = username ; password = password";
    testGetClient(cloudProviderEndpoint, ImClientFactoryTest.paasImUrl, iaasAuthHeader);
  }

  @Test
  @Parameters({ "custom_id", "null" })
  public void testGetClientAzure(@Nullable String headerId) {
    CloudProviderEndpoint cloudProviderEndpoint = new CloudProviderEndpoint();
    cloudProviderEndpoint.setIaasType(IaaSType.AZURE);
    cloudProviderEndpoint.setUsername("username");
    cloudProviderEndpoint.setPassword("password");
    cloudProviderEndpoint.setTenant("subscription_id");
    cloudProviderEndpoint.setCpEndpoint("https://host/");
    cloudProviderEndpoint.setCpComputeServiceId(UUID.randomUUID().toString());
    cloudProviderEndpoint.setIaasHeaderId(headerId);

    String iaasAuthHeader =
        "id = " + (headerId != null ? headerId : "azure")
            + " ; type = Azure ; username = username ; password = password ; subscription_id = subscription_id";
    testGetClient(cloudProviderEndpoint, ImClientFactoryTest.paasImUrl, iaasAuthHeader);
  }

  @Test
  @Parameters({ "custom_id", "null" })
  public void testGetClientOtcOldUsername(@Nullable String headerId) {
    CloudProviderEndpoint cloudProviderEndpoint = new CloudProviderEndpoint();
    cloudProviderEndpoint.setIaasType(IaaSType.OTC);
    cloudProviderEndpoint.setUsername("034 domainInfo");
    cloudProviderEndpoint.setPassword("password");
    cloudProviderEndpoint.setCpEndpoint("https://host/");
    cloudProviderEndpoint.setCpComputeServiceId(UUID.randomUUID().toString());
    cloudProviderEndpoint.setIaasHeaderId(headerId);

    String iaasAuthHeader = "id = " + (headerId != null ? headerId : "ost")
        + " ; type = OpenStack ; domain = domainInfo ; username = 034 domainInfo ; password = password ; tenant = eu-de ; "
        + "auth_version = 3.x_password ;"
        + " host = https://host ; service_name = None ; service_region = eu-de";
    testGetClient(cloudProviderEndpoint, ImClientFactoryTest.paasImUrl, iaasAuthHeader);
  }

  @Test
  @Parameters({ "custom_id", "null" })
  public void testGetClientOtcNewUsername(@Nullable String headerId) {
    CloudProviderEndpoint cloudProviderEndpoint = new CloudProviderEndpoint();
    cloudProviderEndpoint.setIaasType(IaaSType.OTC);
    cloudProviderEndpoint.setUsername("username domainInfo");
    cloudProviderEndpoint.setPassword("password");
    cloudProviderEndpoint.setCpEndpoint("https://host/");
    cloudProviderEndpoint.setCpComputeServiceId(UUID.randomUUID().toString());
    cloudProviderEndpoint.setIaasHeaderId(headerId);

    String iaasAuthHeader = "id = " + (headerId != null ? headerId : "ost")
        + " ; type = OpenStack ; domain = domainInfo ; username = username ; password = password ; tenant = eu-de ; "
        + "auth_version = 3.x_password ;"
        + " host = https://host ; service_name = None ; service_region = eu-de";
    testGetClient(cloudProviderEndpoint, ImClientFactoryTest.paasImUrl, iaasAuthHeader);
  }

  @Test
  @Parameters({ "custom_id", "null" })
  public void testGetClientOtcNewUsernameAndTenantInfo(@Nullable String headerId) {
    CloudProviderEndpoint cloudProviderEndpoint = new CloudProviderEndpoint();
    cloudProviderEndpoint.setIaasType(IaaSType.OTC);
    cloudProviderEndpoint.setUsername("username");
    cloudProviderEndpoint.setPassword("password");
    cloudProviderEndpoint.setCpEndpoint("https://host/");
    cloudProviderEndpoint.setTenant("domainInfo");
    cloudProviderEndpoint.setCpComputeServiceId(UUID.randomUUID().toString());
    cloudProviderEndpoint.setIaasHeaderId(headerId);

    String iaasAuthHeader = "id = " + (headerId != null ? headerId : "ost")
        + " ; type = OpenStack ; domain = domainInfo ; username = username ; password = password ; tenant = eu-de ; "
        + "auth_version = 3.x_password ;"
        + " host = https://host ; service_name = None ; service_region = eu-de";
    testGetClient(cloudProviderEndpoint, ImClientFactoryTest.paasImUrl, iaasAuthHeader);
  }

  private void testGetClient(CloudProviderEndpoint cloudProviderEndpoint,
      String imUrl, String iaasAuthHeader) {
    testGetClient(Lists.newArrayList(cloudProviderEndpoint), imUrl, iaasAuthHeader);
  }

  private void testGetClient(List<CloudProviderEndpoint> cloudProviderEndpoints,
      String imUrl, String iaasAuthHeader) {

    oidcProperties.setEnabled(true);

    InfrastructureManager result = imClientFactory.build(cloudProviderEndpoints, iamToken);
    Assertions
        .assertThat(result)
        .extracting("imClient")
        .extracting("targetUrl", String.class)
        .containsExactly(imUrl);
    Assertions
        .assertThat(result)
        .extracting("imClient")
        .extracting("authorizationHeader", String.class)
        .containsExactly(imTokenAuthHeader + "\\n" + iaasAuthHeader);

  }

  @Test
  @Parameters({ "OPENSTACK", "OTC" })
  public void testOstWrongEndpointFormat(IaaSType iaasType) throws Exception {
    CloudProviderEndpoint cloudProviderEndpoint = new CloudProviderEndpoint();
    cloudProviderEndpoint.setIaasType(iaasType);
    cloudProviderEndpoint.setCpComputeServiceId(UUID.randomUUID().toString());
    cloudProviderEndpoint.setCpEndpoint("lorem.ipsum");
    oidcProperties.setEnabled(true);
    Assertions
        .assertThatCode(
            () -> imClientFactory.build(Lists.newArrayList(cloudProviderEndpoint), iamToken))
        .isInstanceOf(DeploymentException.class)
        .hasMessage("Wrong OS endpoint format: lorem.ipsum");
  }
}