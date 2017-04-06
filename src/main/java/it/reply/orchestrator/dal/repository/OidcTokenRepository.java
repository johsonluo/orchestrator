package it.reply.orchestrator.dal.repository;

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

import it.reply.orchestrator.dal.entity.OidcRefreshToken;
import it.reply.orchestrator.dal.entity.OidcTokenId;

import org.springframework.data.repository.CrudRepository;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;

@Repository
@Transactional(propagation = Propagation.MANDATORY)
public interface OidcTokenRepository extends CrudRepository<OidcRefreshToken, String> {

  public Optional<OidcRefreshToken> findByEntity_OidcEntityId_IssuerAndOriginalTokenId(
      String issuer, String id);

  public default Optional<OidcRefreshToken> findByOidcTokenId(OidcTokenId id) {
    return findByEntity_OidcEntityId_IssuerAndOriginalTokenId(id.getIssuer(), id.getJti());
  }

}