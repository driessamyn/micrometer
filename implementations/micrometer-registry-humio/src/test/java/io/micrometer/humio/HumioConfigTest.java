/*
 * Copyright 2020 VMware, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.micrometer.humio;

import io.micrometer.api.instrument.config.validate.Validated;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class HumioConfigTest {
    private final Map<String, String> props = new HashMap<>();
    private final HumioConfig config = props::get;

    @Test
    void invalid() {
        props.put("humio.uri", "bad");

        assertThat(config.validate().failures().stream().map(Validated.Invalid::getMessage))
                .containsOnly("must be a valid URL");
    }

    @Test
    void valid() {
        assertThat(config.validate().isValid()).isTrue();
    }
}
