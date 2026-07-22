/*
 * Copyright (C) 2014 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.android.manifmerger

import com.android.manifmerger.AttributeModel.BooleanValidator
import com.android.manifmerger.AttributeModel.IntegerValueValidator
import com.android.manifmerger.AttributeModel.SeparatedValuesValidator
import com.android.manifmerger.XmlNode.NodeKey
import junit.framework.TestCase
import org.mockito.Mock
import org.mockito.Mockito
import org.mockito.MockitoAnnotations

/** Tests for the [AttributeModel] class */
class AttributeModelTest : TestCase() {
  @Mock private lateinit var mValidator: AttributeModel.Validator

  @Mock lateinit var mXmlAttribute: XmlAttribute

  @Mock lateinit var mMergingReport: MergingReport.Builder

  @Throws(Exception::class)
  protected override fun setUp() {
    super.setUp()
    MockitoAnnotations.initMocks(this)
    Mockito.`when`(mXmlAttribute.id).thenReturn(NodeKey("Id"))
    Mockito.`when`(mXmlAttribute.printPosition()).thenReturn("Position")
  }

  fun testGetters() {
    var attributeModel =
      AttributeModel.newModel("someName").setIsPackageDependent().setDefaultValue("default_value").setOnReadValidator(mValidator).build()

    assertEquals(XmlNode.fromXmlName("android:someName"), attributeModel.name)
    assertTrue(attributeModel.isPackageDependent)
    assertEquals("default_value", attributeModel.defaultValue)

    attributeModel = AttributeModel.newModel("someName").build()

    assertEquals(XmlNode.fromXmlName("android:someName"), attributeModel.name)
    assertFalse(attributeModel.isPackageDependent)
    assertNull(attributeModel.defaultValue)

    Mockito.verifyNoMoreInteractions(mValidator)
  }

  fun testBooleanValidator() {
    val booleanValidator = BooleanValidator()
    assertTrue(booleanValidator.validates(mMergingReport, mXmlAttribute, "false"))
    assertTrue(booleanValidator.validates(mMergingReport, mXmlAttribute, "true"))
    assertTrue(booleanValidator.validates(mMergingReport, mXmlAttribute, "FALSE"))
    assertTrue(booleanValidator.validates(mMergingReport, mXmlAttribute, "TRUE"))
    assertTrue(booleanValidator.validates(mMergingReport, mXmlAttribute, "False"))
    assertTrue(booleanValidator.validates(mMergingReport, mXmlAttribute, "True"))
    assertTrue(booleanValidator.validates(mMergingReport, mXmlAttribute, "@bool/abc"))
    assertTrue(booleanValidator.validates(mMergingReport, mXmlAttribute, "?bool/abc"))
    assertTrue(booleanValidator.validates(mMergingReport, mXmlAttribute, "@android:bool/abc"))
    assertTrue(booleanValidator.validates(mMergingReport, mXmlAttribute, "?android:bool/abc"))
    assertTrue(booleanValidator.validates(mMergingReport, mXmlAttribute, "@com.example:bool/abc"))
    assertTrue(booleanValidator.validates(mMergingReport, mXmlAttribute, "?com.example:bool/abc"))
    assertFalse(booleanValidator.validates(mMergingReport, mXmlAttribute, "@:bool/abc"))
    assertFalse(booleanValidator.validates(mMergingReport, mXmlAttribute, "@bool/"))
    assertFalse(booleanValidator.validates(mMergingReport, mXmlAttribute, "bool/"))

    assertFalse(booleanValidator.validates(mMergingReport, mXmlAttribute, "foo"))
    Mockito.verify(mMergingReport)
      .addMessage(
        mXmlAttribute,
        MergingReport.Record.Severity.ERROR,
        "Attribute Id at Position has an illegal value=(foo), " + "expected 'true' or 'false'",
      )
  }

  fun testSeparatedValuesValidator() {
    val separatedValuesValidator = SeparatedValuesValidator(",", "foo", "bar", "doh !")
    assertTrue(separatedValuesValidator.validates(mMergingReport, mXmlAttribute, "foo"))
    assertTrue(separatedValuesValidator.validates(mMergingReport, mXmlAttribute, "foo,bar"))
    assertTrue(separatedValuesValidator.validates(mMergingReport, mXmlAttribute, "foo,foo"))
    assertTrue(separatedValuesValidator.validates(mMergingReport, mXmlAttribute, "doh !,bar,foo"))

    assertFalse(separatedValuesValidator.validates(mMergingReport, mXmlAttribute, "oh no !"))
    assertFalse(separatedValuesValidator.validates(mMergingReport, mXmlAttribute, "foo,oh no !"))
    assertFalse(separatedValuesValidator.validates(mMergingReport, mXmlAttribute, ""))
    assertFalse(separatedValuesValidator.validates(mMergingReport, mXmlAttribute, ",,"))
    assertFalse(separatedValuesValidator.validates(mMergingReport, mXmlAttribute, "foo, bar"))
    Mockito.verify(mMergingReport)
      .addMessage(
        mXmlAttribute,
        MergingReport.Record.Severity.ERROR,
        "Invalid value for attribute Id at Position, value=(foo, bar), " + "acceptable delimiter-separated values are (foo,bar,doh !)",
      )
  }

  fun testIntegerValueValidator() {
    val integerValueValidator = IntegerValueValidator()
    assertTrue(integerValueValidator.validates(mMergingReport, mXmlAttribute, "@integer/abc"))
    assertTrue(integerValueValidator.validates(mMergingReport, mXmlAttribute, "?integer/abc"))
    assertTrue(integerValueValidator.validates(mMergingReport, mXmlAttribute, "@android:integer/abc"))
    assertTrue(integerValueValidator.validates(mMergingReport, mXmlAttribute, "@com.example:integer/abc"))
    assertFalse(integerValueValidator.validates(mMergingReport, mXmlAttribute, "@:integer/abc"))
    assertFalse(integerValueValidator.validates(mMergingReport, mXmlAttribute, "@:integer/"))
    assertFalse(integerValueValidator.validates(mMergingReport, mXmlAttribute, "@int/abc"))

    assertFalse(integerValueValidator.validates(mMergingReport, mXmlAttribute, "abcd"))
    assertFalse(integerValueValidator.validates(mMergingReport, mXmlAttribute, "123456789123456789"))
    assertFalse(integerValueValidator.validates(mMergingReport, mXmlAttribute, "0xFFFFFFFFFFFFFFFF"))
    Mockito.verify(mMergingReport)
      .addMessage(
        mXmlAttribute,
        MergingReport.Record.Severity.ERROR,
        "Attribute Id at Position must be an integer, found 0xFFFFFFFFFFFFFFFF",
      )
  }

  fun testStrictMergingPolicy() {
    assertEquals("ok", AttributeModel.STRICT_MERGING_POLICY.merge("ok", "ok"))
    assertNull(AttributeModel.STRICT_MERGING_POLICY.merge("one", "two"))
  }

  fun testOrMergingPolicy() {
    assertEquals("true", AttributeModel.OR_MERGING_POLICY.merge("true", "true"))
    assertEquals("true", AttributeModel.OR_MERGING_POLICY.merge("true", "false"))
    assertEquals("true", AttributeModel.OR_MERGING_POLICY.merge("false", "true"))
    assertEquals("false", AttributeModel.OR_MERGING_POLICY.merge("false", "false"))
  }

  fun testNumericalSuperiorityPolicy() {
    assertEquals("5", AttributeModel.NO_MERGING_POLICY.merge("5", "10"))
    assertEquals("10", AttributeModel.NO_MERGING_POLICY.merge("10", "5"))
  }
}
