/*
 * SonarQube
 * Copyright (C) 2009-2021 SonarSource SA
 * mailto:info AT sonarsource DOT com
 *
 * This program is free software; you can redistribute it and/or
 * modify it under the terms of the GNU Lesser General Public
 * License as published by the Free Software Foundation; either
 * version 3 of the License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
 * Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with this program; if not, write to the Free Software Foundation,
 * Inc., 51 Franklin Street, Fifth Floor, Boston, MA  02110-1301, USA.
 */
package org.sonar.db.property;

import com.tngtech.java.junit.dataprovider.DataProvider;
import com.tngtech.java.junit.dataprovider.DataProviderRunner;
import org.apache.commons.lang.StringUtils;
import org.junit.Test;
import org.junit.runner.RunWith;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@RunWith(DataProviderRunner.class)
public class InternalComponentPropertyDtoTest {


  @Test
  public void setter_and_getter() {
    InternalComponentPropertyDto underTest = new InternalComponentPropertyDto()
      .setComponentUuid("component1")
      .setKey("key1")
      .setValue("value1")
      .setUuid("uuid1")
      .setCreatedAt(10L)
      .setUpdatedAt(15L);

    assertThat(underTest.getComponentUuid()).isEqualTo("component1");
    assertThat(underTest.getKey()).isEqualTo("key1");
    assertThat(underTest.getValue()).isEqualTo("value1");
    assertThat(underTest.getUuid()).isEqualTo("uuid1");
    assertThat(underTest.getCreatedAt()).isEqualTo(10L);
    assertThat(underTest.getUpdatedAt()).isEqualTo(15L);
  }

  @Test
  @DataProvider({"null", ""})
  public void setKey_throws_IAE_if_key_is_null_or_empty(String key) {
    assertThatThrownBy(() -> new InternalComponentPropertyDto().setKey(key))
      .isInstanceOf(IllegalArgumentException.class)
      .hasMessage("key can't be null nor empty");
  }

  @Test
  public void setKey_throws_IAE_if_key_is_too_long() {
    String veryLongKey = StringUtils.repeat("a", 513);

    assertThatThrownBy(() -> new InternalComponentPropertyDto().setKey(veryLongKey))
      .isInstanceOf(IllegalArgumentException.class)
      .hasMessage(String.format("key length (513) is longer than the maximum authorized (512). '%s' was provided", veryLongKey));
  }

  @Test
  public void setValue_throws_IAE_if_value_is_too_long() {
    String veryLongValue = StringUtils.repeat("a", 4001);

    assertThatThrownBy(() -> new InternalComponentPropertyDto().setValue(veryLongValue))
      .isInstanceOf(IllegalArgumentException.class)
      .hasMessage(String.format("value length (4001) is longer than the maximum authorized (4000). '%s' was provided", veryLongValue));
  }

  @Test
  public void setValue_accept_null_value() {
    InternalComponentPropertyDto underTest = new InternalComponentPropertyDto().setValue(null);

    assertThat(underTest.getValue()).isNull();
  }

  @Test
  public void test_toString() {
    InternalComponentPropertyDto underTest = new InternalComponentPropertyDto()
      .setUuid("uuid1")
      .setComponentUuid("component1")
      .setKey("key1")
      .setValue("value1")
      .setCreatedAt(10L)
      .setUpdatedAt(15L);

    assertThat(underTest.toString()).isEqualTo("InternalComponentPropertyDto{uuid=uuid1, key=key1, value=value1, componentUuid=component1, updatedAt=15, createdAt=10}");
  }
}
