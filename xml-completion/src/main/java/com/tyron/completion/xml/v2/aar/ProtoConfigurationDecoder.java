package com.tyron.completion.xml.v2.aar;

import com.android.aapt.ConfigurationOuterClass.Configuration;
import com.android.ide.common.resources.configuration.CountryCodeQualifier;
import com.android.ide.common.resources.configuration.DensityQualifier;
import com.android.ide.common.resources.configuration.FolderConfiguration;
import com.android.ide.common.resources.configuration.HighDynamicRangeQualifier;
import com.android.ide.common.resources.configuration.KeyboardStateQualifier;
import com.android.ide.common.resources.configuration.LayoutDirectionQualifier;
import com.android.ide.common.resources.configuration.LocaleQualifier;
import com.android.ide.common.resources.configuration.NavigationMethodQualifier;
import com.android.ide.common.resources.configuration.NavigationStateQualifier;
import com.android.ide.common.resources.configuration.NetworkCodeQualifier;
import com.android.ide.common.resources.configuration.NightModeQualifier;
import com.android.ide.common.resources.configuration.ScreenHeightQualifier;
import com.android.ide.common.resources.configuration.ScreenOrientationQualifier;
import com.android.ide.common.resources.configuration.ScreenRatioQualifier;
import com.android.ide.common.resources.configuration.ScreenRoundQualifier;
import com.android.ide.common.resources.configuration.ScreenSizeQualifier;
import com.android.ide.common.resources.configuration.ScreenWidthQualifier;
import com.android.ide.common.resources.configuration.SmallestScreenWidthQualifier;
import com.android.ide.common.resources.configuration.TextInputMethodQualifier;
import com.android.ide.common.resources.configuration.TouchScreenQualifier;
import com.android.ide.common.resources.configuration.UiModeQualifier;
import com.android.ide.common.resources.configuration.VersionQualifier;
import com.android.ide.common.resources.configuration.WideGamutColorQualifier;
import com.android.resources.Density;
import com.android.resources.HighDynamicRange;
import com.android.resources.Keyboard;
import com.android.resources.KeyboardState;
import com.android.resources.LayoutDirection;
import com.android.resources.Navigation;
import com.android.resources.NavigationState;
import com.android.resources.NightMode;
import com.android.resources.ScreenOrientation;
import com.android.resources.ScreenRatio;
import com.android.resources.ScreenRound;
import com.android.resources.ScreenSize;
import com.android.resources.TouchScreen;
import com.android.resources.UiMode;
import com.android.resources.WideGamutColor;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Converts {@code aapt.pb.Configuration} proto message into a {@link FolderConfiguration} object.
 */
class ProtoConfigurationDecoder {
  @NotNull
  static FolderConfiguration getConfiguration(@NotNull Configuration configMsg) {
    FolderConfiguration configuration = new FolderConfiguration();

    int mcc = configMsg.getMcc();
    if (mcc != 0) {
      configuration.setCountryCodeQualifier(new CountryCodeQualifier(mcc));
    }

    int mnc = configMsg.getMnc();
    if (mnc != 0) {
      configuration.setNetworkCodeQualifier(new NetworkCodeQualifier(mnc));
    }

    String locale = configMsg.getLocale();
    if (!locale.isEmpty()) {
      LocaleQualifier qualifier = LocaleQualifier.getQualifier(locale);
      if (qualifier == null) {
        locale = "b+" + locale.replace('-', '+');
        qualifier = LocaleQualifier.getQualifier(locale);
      }
      configuration.setLocaleQualifier(qualifier);
    }

    LayoutDirection layoutDirection = getLayoutDirection(configMsg.getLayoutDirection());
    if (layoutDirection != null) {
      configuration.setLayoutDirectionQualifier(new LayoutDirectionQualifier(layoutDirection));
    }

    int screenWidthDp = configMsg.getScreenWidthDp();
    if (screenWidthDp != 0) {
      configuration.setScreenWidthQualifier(new ScreenWidthQualifier(screenWidthDp));
    }

    int screenHeightDp = configMsg.getScreenHeightDp();
    if (screenHeightDp != 0) {
      configuration.setScreenHeightQualifier(new ScreenHeightQualifier(screenHeightDp));
    }

    int smallestScreenWidthDp = configMsg.getSmallestScreenWidthDp();
    if (smallestScreenWidthDp != 0) {
      configuration.setSmallestScreenWidthQualifier(new SmallestScreenWidthQualifier(smallestScreenWidthDp));
    }

    ScreenSize screenSize = getScreenSize(configMsg.getScreenLayoutSize());
    if (screenSize != null) {
      configuration.setScreenSizeQualifier(new ScreenSizeQualifier(screenSize));
    }

    ScreenRatio screenRatio = getScreenRatio(configMsg.getScreenLayoutLong());
    if (screenRatio != null) {
      configuration.setScreenRatioQualifier(new ScreenRatioQualifier(screenRatio));
    }

    ScreenRound screenRound = getScreenRound(configMsg.getScreenRound());
    if (screenRound != null) {
      configuration.setScreenRoundQualifier(new ScreenRoundQualifier(screenRound));
    }

    WideGamutColor wideGamutColor = getWideGamutColor(configMsg.getWideColorGamut());
    if (wideGamutColor != null) {
      configuration.setWideColorGamutQualifier(new WideGamutColorQualifier(wideGamutColor));
    }

    HighDynamicRange highDynamicRange = getHighDynamicRange(configMsg.getHdr());
    if (highDynamicRange != null) {
      configuration.setHighDynamicRangeQualifier(new HighDynamicRangeQualifier(highDynamicRange));
    }

    ScreenOrientation screenOrientation = getScreenOrientation(configMsg.getOrientation());
    if (screenOrientation != null) {
      configuration.setScreenOrientationQualifier(new ScreenOrientationQualifier(screenOrientation));
    }

    UiMode uiMode = getUiMode(configMsg.getUiModeType());
    if (uiMode != null) {
      configuration.setUiModeQualifier(new UiModeQualifier(uiMode));
    }

    NightMode nightMode = getNightMode(configMsg.getUiModeNight());
    if (nightMode != null) {
      configuration.setNightModeQualifier(new NightModeQualifier(nightMode));
    }

    int densityDpi = configMsg.getDensity();
    if (densityDpi != 0) {
      Density density = Density.getEnum(densityDpi);
      if (density != null) {
        configuration.setDensityQualifier(new DensityQualifier(density));
      }
    }

    TouchScreen touchScreen = getTouchScreen(configMsg.getTouchscreen());
    if (touchScreen != null) {
      configuration.setTouchTypeQualifier(new TouchScreenQualifier(touchScreen));
    }

    KeyboardState keyboardState = getKeyboardState(configMsg.getKeysHidden());
    if (keyboardState != null) {
      configuration.setKeyboardStateQualifier(new KeyboardStateQualifier(keyboardState));
    }

    Keyboard keyboard = getKeyboard(configMsg.getKeyboard());
    if (keyboard != null) {
      configuration.setTextInputMethodQualifier(new TextInputMethodQualifier(keyboard));
    }

    NavigationState navigationState = getNavigationState(configMsg.getNavHidden());
    if (navigationState != null) {
      configuration.setNavigationStateQualifier(new NavigationStateQualifier(navigationState));
    }

    Navigation navigation = getNavigation(configMsg.getNavigation());
    if (navigation != null) {
      configuration.setNavigationMethodQualifier(new NavigationMethodQualifier(navigation));
    }

    int sdkVersion = configMsg.getSdkVersion();
    if (sdkVersion != 0) {
      configuration.setVersionQualifier(new VersionQualifier(sdkVersion));
    }
    return configuration;
  }

  @Nullable
  private static LayoutDirection getLayoutDirection(@NotNull Configuration.LayoutDirection protoValue) {
    switch (protoValue) {
      case LAYOUT_DIRECTION_LTR:
        return LayoutDirection.LTR;
      case LAYOUT_DIRECTION_RTL:
        return LayoutDirection.RTL;
      default:
        return null;
    }
  }

  @Nullable
  private static ScreenSize getScreenSize(@NotNull Configuration.ScreenLayoutSize protoValue) {
    switch (protoValue) {
      case SCREEN_LAYOUT_SIZE_SMALL:
        return ScreenSize.SMALL;
      case SCREEN_LAYOUT_SIZE_NORMAL:
        return ScreenSize.NORMAL;
      case SCREEN_LAYOUT_SIZE_LARGE:
        return ScreenSize.LARGE;
      case SCREEN_LAYOUT_SIZE_XLARGE:
        return ScreenSize.XLARGE;
      default:
        return null;
    }
  }

  @Nullable
  private static ScreenRatio getScreenRatio(@NotNull Configuration.ScreenLayoutLong protoValue) {
    switch (protoValue) {
      case SCREEN_LAYOUT_LONG_NOTLONG:
        return ScreenRatio.NOTLONG;
      case SCREEN_LAYOUT_LONG_LONG:
        return ScreenRatio.LONG;
      default:
        return null;
    }
  }

  @Nullable
  private static ScreenRound getScreenRound(@NotNull Configuration.ScreenRound protoValue) {
    switch (protoValue) {
      case SCREEN_ROUND_NOTROUND:
        return ScreenRound.NOTROUND;
      case SCREEN_ROUND_ROUND:
        return ScreenRound.ROUND;
      default:
        return null;
    }
  }

  @Nullable
  private static WideGamutColor getWideGamutColor(@NotNull Configuration.WideColorGamut protoValue) {
    switch (protoValue) {
      case WIDE_COLOR_GAMUT_WIDECG:
        return WideGamutColor.WIDECG;
      case WIDE_COLOR_GAMUT_NOWIDECG:
        return WideGamutColor.NOWIDECG;
      default:
        return null;
    }
  }

  @Nullable
  private static HighDynamicRange getHighDynamicRange(@NotNull Configuration.Hdr protoValue) {
    switch (protoValue) {
      case HDR_HIGHDR:
        return HighDynamicRange.HIGHDR;
      case HDR_LOWDR:
        return HighDynamicRange.LOWDR;
      default:
        return null;
    }
  }

  @Nullable
  private static ScreenOrientation getScreenOrientation(@NotNull Configuration.Orientation protoValue) {
    switch (protoValue) {
      case ORIENTATION_PORT:
        return ScreenOrientation.PORTRAIT;
      case ORIENTATION_LAND:
        return ScreenOrientation.LANDSCAPE;
      case ORIENTATION_SQUARE:
        return ScreenOrientation.SQUARE;
      default:
        return null;
    }
  }

  @Nullable
  private static UiMode getUiMode(@NotNull Configuration.UiModeType protoValue) {
    switch (protoValue) {
      case UI_MODE_TYPE_NORMAL:
        return UiMode.NORMAL;
      case UI_MODE_TYPE_CAR:
        return UiMode.CAR;
      case UI_MODE_TYPE_DESK:
        return UiMode.DESK;
      case UI_MODE_TYPE_TELEVISION:
        return UiMode.TELEVISION;
      case UI_MODE_TYPE_APPLIANCE:
        return UiMode.APPLIANCE;
      case UI_MODE_TYPE_WATCH:
        return UiMode.WATCH;
      case UI_MODE_TYPE_VRHEADSET:
        return UiMode.VR_HEADSET;
      default:
        return null;
    }
  }

  @Nullable
  private static NightMode getNightMode(@NotNull Configuration.UiModeNight protoValue) {
    switch (protoValue) {
      case UI_MODE_NIGHT_NOTNIGHT:
        return NightMode.NOTNIGHT;
      case UI_MODE_NIGHT_NIGHT:
        return NightMode.NIGHT;
      default:
        return null;
    }
  }

  @Nullable
  private static TouchScreen getTouchScreen(@NotNull Configuration.Touchscreen protoValue) {
    switch (protoValue) {
      case TOUCHSCREEN_NOTOUCH:
        return TouchScreen.NOTOUCH;
      case TOUCHSCREEN_STYLUS:
        return TouchScreen.STYLUS;
      case TOUCHSCREEN_FINGER:
        return TouchScreen.FINGER;
      default:
        return null;
    }
  }

  @Nullable
  private static KeyboardState getKeyboardState(@NotNull Configuration.KeysHidden protoValue) {
    switch (protoValue) {
      case KEYS_HIDDEN_KEYSEXPOSED:
        return KeyboardState.EXPOSED;
      case KEYS_HIDDEN_KEYSHIDDEN:
        return KeyboardState.HIDDEN;
      case KEYS_HIDDEN_KEYSSOFT:
        return KeyboardState.SOFT;
      default:
        return null;
    }
  }

  @Nullable
  private static Keyboard getKeyboard(@NotNull Configuration.Keyboard protoValue) {
    switch (protoValue) {
      case KEYBOARD_NOKEYS:
        return Keyboard.NOKEY;
      case KEYBOARD_QWERTY:
        return Keyboard.QWERTY;
      case KEYBOARD_TWELVEKEY:
        return Keyboard.TWELVEKEY;
      default:
        return null;
    }
  }

  @Nullable
  private static NavigationState getNavigationState(@NotNull Configuration.NavHidden protoValue) {
    switch (protoValue) {
      case NAV_HIDDEN_NAVEXPOSED:
        return NavigationState.EXPOSED;
      case NAV_HIDDEN_NAVHIDDEN:
        return NavigationState.HIDDEN;
      default:
        return null;
    }
  }

  @Nullable
  private static Navigation getNavigation(@NotNull Configuration.Navigation protoValue) {
    switch (protoValue) {
      case NAVIGATION_NONAV:
        return Navigation.NONAV;
      case NAVIGATION_DPAD:
        return Navigation.DPAD;
      case NAVIGATION_TRACKBALL:
        return Navigation.TRACKBALL;
      case NAVIGATION_WHEEL:
        return Navigation.WHEEL;
      default:
        return null;
    }
  }

  /** Do not instantiate. All methods are static. */
  private ProtoConfigurationDecoder() {}
}