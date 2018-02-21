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

package it.reply.orchestrator.service.commands;

import static org.mockito.Matchers.*;
import static org.mockito.Mockito.*;

import org.flowable.engine.impl.persistence.entity.ExecutionEntity;

import java.util.UUID;

public class ExecutionEntityBuilder {

  private ExecutionEntity execution;

  public ExecutionEntityBuilder() {
    execution = mock(ExecutionEntity.class);
    when(execution.getProcessInstanceBusinessKey())
        .thenReturn(UUID.randomUUID().toString() + ":" + UUID.randomUUID().toString());
  }

  public ExecutionEntityBuilder withMockedVariable(String key, Object value) {
    when(execution.getVariable(eq(key), anyBoolean())).thenReturn(value);
    return this;
  }

  public ExecutionEntityBuilder withMockedVariableLocal(String key, Object value) {
    when(execution.getVariableLocal(eq(key), anyBoolean())).thenReturn(value);
    return this;
  }

  public ExecutionEntityBuilder withMockedTransientVariable(String key, Object value) {
    when(execution.getTransientVariable(eq(key))).thenReturn(value);
    return this;
  }

  public ExecutionEntityBuilder withMockedTransientVariableLocal(String key, Object value) {
    when(execution.getTransientVariableLocal(eq(key))).thenReturn(value);
    return this;
  }

  public ExecutionEntity build() {
    return execution;
  }
}