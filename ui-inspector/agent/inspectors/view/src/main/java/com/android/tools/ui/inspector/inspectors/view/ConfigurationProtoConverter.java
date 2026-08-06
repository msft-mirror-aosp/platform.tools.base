/*
 * Copyright (C) 2026 The Android Open Source Project
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

package com.android.tools.ui.inspector.inspectors.view;

import android.os.Build;

import com.android.tools.ui.inspector.view.inspector.protocol.ViewInspectorProtocol.ColorModeHdr;
import com.android.tools.ui.inspector.view.inspector.protocol.ViewInspectorProtocol.ColorModeWideGamut;
import com.android.tools.ui.inspector.view.inspector.protocol.ViewInspectorProtocol.Configuration;
import com.android.tools.ui.inspector.view.inspector.protocol.ViewInspectorProtocol.GrammaticalGender;
import com.android.tools.ui.inspector.view.inspector.protocol.ViewInspectorProtocol.HardKeyboardHidden;
import com.android.tools.ui.inspector.view.inspector.protocol.ViewInspectorProtocol.Keyboard;
import com.android.tools.ui.inspector.view.inspector.protocol.ViewInspectorProtocol.KeyboardHidden;
import com.android.tools.ui.inspector.view.inspector.protocol.ViewInspectorProtocol.LayoutDirection;
import com.android.tools.ui.inspector.view.inspector.protocol.ViewInspectorProtocol.Locale;
import com.android.tools.ui.inspector.view.inspector.protocol.ViewInspectorProtocol.Navigation;
import com.android.tools.ui.inspector.view.inspector.protocol.ViewInspectorProtocol.NavigationHidden;
import com.android.tools.ui.inspector.view.inspector.protocol.ViewInspectorProtocol.Orientation;
import com.android.tools.ui.inspector.view.inspector.protocol.ViewInspectorProtocol.ScreenLayoutLong;
import com.android.tools.ui.inspector.view.inspector.protocol.ViewInspectorProtocol.ScreenLayoutRound;
import com.android.tools.ui.inspector.view.inspector.protocol.ViewInspectorProtocol.ScreenLayoutSize;
import com.android.tools.ui.inspector.view.inspector.protocol.ViewInspectorProtocol.TouchScreen;
import com.android.tools.ui.inspector.view.inspector.protocol.ViewInspectorProtocol.UiModeNight;
import com.android.tools.ui.inspector.view.inspector.protocol.ViewInspectorProtocol.UiModeType;

/**
 * Converts Android resource {@link android.content.res.Configuration} and {@link java.util.Locale}
 * values into proto representations.
 */
final class ConfigurationProtoConverter {
    private ConfigurationProtoConverter() {}

    @SuppressWarnings("deprecation") // Configuration.locale is the only locale source below API 24.
    static Configuration convert(
            android.content.res.Configuration config, StringTable stringTable) {
        Configuration.Builder builder = Configuration.newBuilder();
        builder.setFontScale(config.fontScale);
        builder.setCountryCode(config.mcc);
        builder.setNetworkCode(config.mnc);
        builder.setSmallestScreenWidthDp(config.smallestScreenWidthDp);
        builder.setDensity(config.densityDpi);
        builder.setScreenWidthDp(config.screenWidthDp);
        builder.setScreenHeightDp(config.screenHeightDp);

        if (Build.VERSION.SDK_INT >= 24) {
            if (!config.getLocales().isEmpty()) {
                builder.setLocale(convert(config.getLocales().get(0), stringTable));
            }
        } else if (config.locale != null) {
            builder.setLocale(convert(config.locale, stringTable));
        }

        populateScreenLayout(builder, config.screenLayout);
        if (Build.VERSION.SDK_INT >= 26) {
            populateColorMode(builder, config.colorMode);
        }

        // populate touchScreen
        switch (config.touchscreen) {
            case android.content.res.Configuration.TOUCHSCREEN_NOTOUCH:
                builder.setTouchScreen(TouchScreen.TOUCH_SCREEN_NOTOUCH);
                break;
            case android.content.res.Configuration.TOUCHSCREEN_STYLUS:
                builder.setTouchScreen(TouchScreen.TOUCH_SCREEN_STYLUS);
                break;
            case android.content.res.Configuration.TOUCHSCREEN_FINGER:
                builder.setTouchScreen(TouchScreen.TOUCH_SCREEN_FINGER);
                break;
            default:
                builder.setTouchScreen(TouchScreen.TOUCH_SCREEN_UNDEFINED);
                break;
        }

        // populate keyboard
        switch (config.keyboard) {
            case android.content.res.Configuration.KEYBOARD_NOKEYS:
                builder.setKeyboard(Keyboard.KEYBOARD_NOKEYS);
                break;
            case android.content.res.Configuration.KEYBOARD_QWERTY:
                builder.setKeyboard(Keyboard.KEYBOARD_QWERTY);
                break;
            case android.content.res.Configuration.KEYBOARD_12KEY:
                builder.setKeyboard(Keyboard.KEYBOARD_12KEY);
                break;
            default:
                builder.setKeyboard(Keyboard.KEYBOARD_UNDEFINED);
                break;
        }

        // populate keyboardHidden
        switch (config.keyboardHidden) {
            case android.content.res.Configuration.KEYBOARDHIDDEN_NO:
                builder.setKeyboardHidden(KeyboardHidden.KEYBOARD_HIDDEN_NO);
                break;
            case android.content.res.Configuration.KEYBOARDHIDDEN_YES:
                builder.setKeyboardHidden(KeyboardHidden.KEYBOARD_HIDDEN_YES);
                break;
            default:
                builder.setKeyboardHidden(KeyboardHidden.KEYBOARD_HIDDEN_UNDEFINED);
                break;
        }

        // populate hardKeyboardHidden
        switch (config.hardKeyboardHidden) {
            case android.content.res.Configuration.HARDKEYBOARDHIDDEN_NO:
                builder.setHardKeyboardHidden(HardKeyboardHidden.HARD_KEYBOARD_HIDDEN_NO);
                break;
            case android.content.res.Configuration.HARDKEYBOARDHIDDEN_YES:
                builder.setHardKeyboardHidden(HardKeyboardHidden.HARD_KEYBOARD_HIDDEN_YES);
                break;
            default:
                builder.setHardKeyboardHidden(HardKeyboardHidden.HARD_KEYBOARD_HIDDEN_UNDEFINED);
                break;
        }

        // populate navigation
        switch (config.navigation) {
            case android.content.res.Configuration.NAVIGATION_NONAV:
                builder.setNavigation(Navigation.NAVIGATION_NONAV);
                break;
            case android.content.res.Configuration.NAVIGATION_DPAD:
                builder.setNavigation(Navigation.NAVIGATION_DPAD);
                break;
            case android.content.res.Configuration.NAVIGATION_TRACKBALL:
                builder.setNavigation(Navigation.NAVIGATION_TRACKBALL);
                break;
            case android.content.res.Configuration.NAVIGATION_WHEEL:
                builder.setNavigation(Navigation.NAVIGATION_WHEEL);
                break;
            default:
                builder.setNavigation(Navigation.NAVIGATION_UNDEFINED);
                break;
        }

        // populate navigationHidden
        switch (config.navigationHidden) {
            case android.content.res.Configuration.NAVIGATIONHIDDEN_NO:
                builder.setNavigationHidden(NavigationHidden.NAVIGATION_HIDDEN_NO);
                break;
            case android.content.res.Configuration.NAVIGATIONHIDDEN_YES:
                builder.setNavigationHidden(NavigationHidden.NAVIGATION_HIDDEN_YES);
                break;
            default:
                builder.setNavigationHidden(NavigationHidden.NAVIGATION_HIDDEN_UNDEFINED);
                break;
        }

        // populate uiMode (type, night)
        populateUiMode(builder, config.uiMode);

        // orientation
        switch (config.orientation) {
            case android.content.res.Configuration.ORIENTATION_PORTRAIT:
                builder.setOrientation(Orientation.ORIENTATION_PORTRAIT);
                break;
            case android.content.res.Configuration.ORIENTATION_LANDSCAPE:
                builder.setOrientation(Orientation.ORIENTATION_LANDSCAPE);
                break;
            case android.content.res.Configuration.ORIENTATION_SQUARE:
                builder.setOrientation(Orientation.ORIENTATION_SQUARE);
                break;
            default:
                builder.setOrientation(Orientation.ORIENTATION_UNDEFINED);
                break;
        }

        // populate grammaticalGender
        if (Build.VERSION.SDK_INT >= 34) {
            switch (config.getGrammaticalGender()) {
                case android.content.res.Configuration.GRAMMATICAL_GENDER_NEUTRAL:
                    builder.setGrammaticalGender(GrammaticalGender.GRAMMATICAL_GENDER_NEUTRAL);
                    break;
                case android.content.res.Configuration.GRAMMATICAL_GENDER_FEMININE:
                    builder.setGrammaticalGender(GrammaticalGender.GRAMMATICAL_GENDER_FEMININE);
                    break;
                case android.content.res.Configuration.GRAMMATICAL_GENDER_MASCULINE:
                    builder.setGrammaticalGender(GrammaticalGender.GRAMMATICAL_GENDER_MASCULINE);
                    break;
                default:
                    builder.setGrammaticalGender(GrammaticalGender.GRAMMATICAL_GENDER_UNDEFINED);
                    break;
            }
        }

        return builder.build();
    }

    private static void populateScreenLayout(Configuration.Builder builder, int screenLayout) {
        int sizeVal = screenLayout & android.content.res.Configuration.SCREENLAYOUT_SIZE_MASK;
        switch (sizeVal) {
            case android.content.res.Configuration.SCREENLAYOUT_SIZE_SMALL:
                builder.setScreenLayoutSize(ScreenLayoutSize.SCREEN_LAYOUT_SIZE_SMALL);
                break;
            case android.content.res.Configuration.SCREENLAYOUT_SIZE_NORMAL:
                builder.setScreenLayoutSize(ScreenLayoutSize.SCREEN_LAYOUT_SIZE_NORMAL);
                break;
            case android.content.res.Configuration.SCREENLAYOUT_SIZE_LARGE:
                builder.setScreenLayoutSize(ScreenLayoutSize.SCREEN_LAYOUT_SIZE_LARGE);
                break;
            case android.content.res.Configuration.SCREENLAYOUT_SIZE_XLARGE:
                builder.setScreenLayoutSize(ScreenLayoutSize.SCREEN_LAYOUT_SIZE_XLARGE);
                break;
            default:
                builder.setScreenLayoutSize(ScreenLayoutSize.SCREEN_LAYOUT_SIZE_UNDEFINED);
                break;
        }

        int longVal = screenLayout & android.content.res.Configuration.SCREENLAYOUT_LONG_MASK;
        switch (longVal) {
            case android.content.res.Configuration.SCREENLAYOUT_LONG_NO:
                builder.setScreenLayoutLong(ScreenLayoutLong.SCREEN_LAYOUT_LONG_NO);
                break;
            case android.content.res.Configuration.SCREENLAYOUT_LONG_YES:
                builder.setScreenLayoutLong(ScreenLayoutLong.SCREEN_LAYOUT_LONG_YES);
                break;
            default:
                builder.setScreenLayoutLong(ScreenLayoutLong.SCREEN_LAYOUT_LONG_UNDEFINED);
                break;
        }

        int layoutDirVal =
                screenLayout & android.content.res.Configuration.SCREENLAYOUT_LAYOUTDIR_MASK;
        switch (layoutDirVal) {
            case android.content.res.Configuration.SCREENLAYOUT_LAYOUTDIR_LTR:
                builder.setLayoutDirection(LayoutDirection.LAYOUT_DIRECTION_LTR);
                break;
            case android.content.res.Configuration.SCREENLAYOUT_LAYOUTDIR_RTL:
                builder.setLayoutDirection(LayoutDirection.LAYOUT_DIRECTION_RTL);
                break;
            default:
                builder.setLayoutDirection(LayoutDirection.LAYOUT_DIRECTION_UNDEFINED);
                break;
        }

        int roundVal = screenLayout & android.content.res.Configuration.SCREENLAYOUT_ROUND_MASK;
        switch (roundVal) {
            case android.content.res.Configuration.SCREENLAYOUT_ROUND_NO:
                builder.setScreenLayoutRound(ScreenLayoutRound.SCREEN_LAYOUT_ROUND_NO);
                break;
            case android.content.res.Configuration.SCREENLAYOUT_ROUND_YES:
                builder.setScreenLayoutRound(ScreenLayoutRound.SCREEN_LAYOUT_ROUND_YES);
                break;
            default:
                builder.setScreenLayoutRound(ScreenLayoutRound.SCREEN_LAYOUT_ROUND_UNDEFINED);
                break;
        }
    }

    private static void populateColorMode(Configuration.Builder builder, int colorMode) {
        int wideGamutVal =
                colorMode & android.content.res.Configuration.COLOR_MODE_WIDE_COLOR_GAMUT_MASK;
        switch (wideGamutVal) {
            case android.content.res.Configuration.COLOR_MODE_WIDE_COLOR_GAMUT_NO:
                builder.setColorModeWideGamut(ColorModeWideGamut.COLOR_MODE_WIDE_GAMUT_NO);
                break;
            case android.content.res.Configuration.COLOR_MODE_WIDE_COLOR_GAMUT_YES:
                builder.setColorModeWideGamut(ColorModeWideGamut.COLOR_MODE_WIDE_GAMUT_YES);
                break;
            default:
                builder.setColorModeWideGamut(ColorModeWideGamut.COLOR_MODE_WIDE_GAMUT_UNDEFINED);
                break;
        }

        int hdrVal = colorMode & android.content.res.Configuration.COLOR_MODE_HDR_MASK;
        switch (hdrVal) {
            case android.content.res.Configuration.COLOR_MODE_HDR_NO:
                builder.setColorModeHdr(ColorModeHdr.COLOR_MODE_HDR_NO);
                break;
            case android.content.res.Configuration.COLOR_MODE_HDR_YES:
                builder.setColorModeHdr(ColorModeHdr.COLOR_MODE_HDR_YES);
                break;
            default:
                builder.setColorModeHdr(ColorModeHdr.COLOR_MODE_HDR_UNDEFINED);
                break;
        }
    }

    private static void populateUiMode(Configuration.Builder builder, int uiMode) {
        int uiModeTypeVal = uiMode & android.content.res.Configuration.UI_MODE_TYPE_MASK;
        switch (uiModeTypeVal) {
            case android.content.res.Configuration.UI_MODE_TYPE_NORMAL:
                builder.setUiModeType(UiModeType.UI_MODE_TYPE_NORMAL);
                break;
            case android.content.res.Configuration.UI_MODE_TYPE_DESK:
                builder.setUiModeType(UiModeType.UI_MODE_TYPE_DESK);
                break;
            case android.content.res.Configuration.UI_MODE_TYPE_CAR:
                builder.setUiModeType(UiModeType.UI_MODE_TYPE_CAR);
                break;
            case android.content.res.Configuration.UI_MODE_TYPE_TELEVISION:
                builder.setUiModeType(UiModeType.UI_MODE_TYPE_TELEVISION);
                break;
            case android.content.res.Configuration.UI_MODE_TYPE_APPLIANCE:
                builder.setUiModeType(UiModeType.UI_MODE_TYPE_APPLIANCE);
                break;
            case android.content.res.Configuration.UI_MODE_TYPE_WATCH:
                builder.setUiModeType(UiModeType.UI_MODE_TYPE_WATCH);
                break;
            case android.content.res.Configuration.UI_MODE_TYPE_VR_HEADSET:
                builder.setUiModeType(UiModeType.UI_MODE_TYPE_VR_HEADSET);
                break;
            default:
                builder.setUiModeType(UiModeType.UI_MODE_TYPE_UNDEFINED);
                break;
        }

        int uiModeNightVal = uiMode & android.content.res.Configuration.UI_MODE_NIGHT_MASK;
        switch (uiModeNightVal) {
            case android.content.res.Configuration.UI_MODE_NIGHT_NO:
                builder.setUiModeNight(UiModeNight.UI_MODE_NIGHT_NO);
                break;
            case android.content.res.Configuration.UI_MODE_NIGHT_YES:
                builder.setUiModeNight(UiModeNight.UI_MODE_NIGHT_YES);
                break;
            default:
                builder.setUiModeNight(UiModeNight.UI_MODE_NIGHT_UNDEFINED);
                break;
        }
    }

    static Locale convert(java.util.Locale locale, StringTable stringTable) {
        return Locale.newBuilder()
                .setLanguage(stringTable.put(locale.getLanguage()))
                .setCountry(stringTable.put(locale.getCountry()))
                .setVariant(stringTable.put(locale.getVariant()))
                .setScript(stringTable.put(locale.getScript()))
                .build();
    }
}
