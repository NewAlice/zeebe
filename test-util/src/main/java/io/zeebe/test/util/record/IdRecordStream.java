/*
 * Copyright © 2017 camunda services GmbH (info@camunda.com)
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
package io.zeebe.test.util.record;

import io.zeebe.exporter.record.Record;
import io.zeebe.exporter.record.value.IdRecordValue;
import java.util.stream.Stream;

public class IdRecordStream extends ExporterRecordStream<IdRecordValue, IdRecordStream> {

  public IdRecordStream(final Stream<Record<IdRecordValue>> wrappedStream) {
    super(wrappedStream);
  }

  @Override
  protected IdRecordStream supply(final Stream<Record<IdRecordValue>> wrappedStream) {
    return new IdRecordStream(wrappedStream);
  }

  public IdRecordStream withId(final int id) {
    return valueFilter(v -> v.getId() == id);
  }
}