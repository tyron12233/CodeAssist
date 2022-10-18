package com.android.aaptcompiler.proto

import com.android.aapt.ConfigurationOuterClass
import com.android.aapt.Resources
import com.android.aaptcompiler.*
import com.android.aaptcompiler.android.*
import com.android.resources.ResourceVisibility
import com.android.utils.ILogger

fun deserializeConfigFromPb(
    config: ConfigurationOuterClass.Configuration, logger: ILogger?): ConfigDescription? {

    val mcc = config.mcc.toShort()
    val mnc = config.mnc.toShort()

    val localeString = config.locale
    val localeValue = LocaleValue()
    if (localeString.isNotEmpty()) {
        if (!localeValue.initFromBcp47Tag(localeString)) {
            logger?.error(null, "Failed to initialized from Bcp47 '%s'", localeString)
            return null
        }
    }

    val screenLayoutDir = when (config.layoutDirection) {
        ConfigurationOuterClass.Configuration.LayoutDirection.LAYOUT_DIRECTION_LTR ->
            ResTableConfig.SCREEN_LAYOUT.DIR_LTR
        ConfigurationOuterClass.Configuration.LayoutDirection.LAYOUT_DIRECTION_RTL ->
            ResTableConfig.SCREEN_LAYOUT.DIR_RTL
        else -> ResTableConfig.SCREEN_LAYOUT.DIR_ANY
    }

    val smallestScreenWidthDp = config.smallestScreenWidthDp
    val screenWidthDp = config.screenWidthDp
    val screenHeightDp = config.screenHeightDp

    val screenLayoutSize = when (config.screenLayoutSize) {
        ConfigurationOuterClass.Configuration.ScreenLayoutSize.SCREEN_LAYOUT_SIZE_SMALL ->
            ResTableConfig.SCREEN_LAYOUT.SIZE_SMALL
        ConfigurationOuterClass.Configuration.ScreenLayoutSize.SCREEN_LAYOUT_SIZE_NORMAL ->
            ResTableConfig.SCREEN_LAYOUT.SIZE_NORMAL
        ConfigurationOuterClass.Configuration.ScreenLayoutSize.SCREEN_LAYOUT_SIZE_LARGE ->
            ResTableConfig.SCREEN_LAYOUT.SIZE_LARGE
        ConfigurationOuterClass.Configuration.ScreenLayoutSize.SCREEN_LAYOUT_SIZE_XLARGE ->
            ResTableConfig.SCREEN_LAYOUT.SIZE_XLARGE
        else -> ResTableConfig.SCREEN_LAYOUT.SIZE_ANY
    }

    val screenLayoutLong = when (config.screenLayoutLong) {
        ConfigurationOuterClass.Configuration.ScreenLayoutLong.SCREEN_LAYOUT_LONG_LONG ->
            ResTableConfig.SCREEN_LAYOUT.SCREENLONG_YES
        ConfigurationOuterClass.Configuration.ScreenLayoutLong.SCREEN_LAYOUT_LONG_NOTLONG ->
            ResTableConfig.SCREEN_LAYOUT.SCREENLONG_NO
        else -> ResTableConfig.SCREEN_LAYOUT.SCREENLONG_ANY
    }

    val screenRound = when (config.screenRound) {
        ConfigurationOuterClass.Configuration.ScreenRound.SCREEN_ROUND_ROUND ->
            ResTableConfig.SCREEN_LAYOUT2.SCREENROUND_YES
        ConfigurationOuterClass.Configuration.ScreenRound.SCREEN_ROUND_NOTROUND ->
            ResTableConfig.SCREEN_LAYOUT2.SCREENROUND_NO
        else -> ResTableConfig.SCREEN_LAYOUT2.SCREENROUND_ANY
    }

    val wideGamutColor = when (config.wideColorGamut) {
        ConfigurationOuterClass.Configuration.WideColorGamut.WIDE_COLOR_GAMUT_WIDECG ->
            ResTableConfig.COLOR_MODE.WIDE_GAMUT_YES
        ConfigurationOuterClass.Configuration.WideColorGamut.WIDE_COLOR_GAMUT_NOWIDECG ->
            ResTableConfig.COLOR_MODE.WIDE_GAMUT_NO
        else -> ResTableConfig.COLOR_MODE.WIDE_GAMUT_ANY
    }

    val hdr = when (config.hdr) {
        ConfigurationOuterClass.Configuration.Hdr.HDR_HIGHDR -> ResTableConfig.COLOR_MODE.HDR_YES
        ConfigurationOuterClass.Configuration.Hdr.HDR_LOWDR -> ResTableConfig.COLOR_MODE.HDR_NO
        else -> ResTableConfig.COLOR_MODE.HDR_ANY
    }

    val orientation = when (config.orientation) {
        ConfigurationOuterClass.Configuration.Orientation.ORIENTATION_PORT ->
            ResTableConfig.ORIENTATION.PORT
        ConfigurationOuterClass.Configuration.Orientation.ORIENTATION_LAND ->
            ResTableConfig.ORIENTATION.LAND
        ConfigurationOuterClass.Configuration.Orientation.ORIENTATION_SQUARE ->
            ResTableConfig.ORIENTATION.SQUARE
        else -> ResTableConfig.ORIENTATION.ANY
    }

    val uiModeType = when (config.uiModeType) {
        ConfigurationOuterClass.Configuration.UiModeType.UI_MODE_TYPE_NORMAL ->
            ResTableConfig.UI_MODE.TYPE_NORMAL
        ConfigurationOuterClass.Configuration.UiModeType.UI_MODE_TYPE_DESK ->
            ResTableConfig.UI_MODE.TYPE_DESK
        ConfigurationOuterClass.Configuration.UiModeType.UI_MODE_TYPE_CAR ->
            ResTableConfig.UI_MODE.TYPE_CAR
        ConfigurationOuterClass.Configuration.UiModeType.UI_MODE_TYPE_TELEVISION ->
            ResTableConfig.UI_MODE.TYPE_TELEVISION
        ConfigurationOuterClass.Configuration.UiModeType.UI_MODE_TYPE_APPLIANCE ->
            ResTableConfig.UI_MODE.TYPE_APPLIANCE
        ConfigurationOuterClass.Configuration.UiModeType.UI_MODE_TYPE_WATCH ->
            ResTableConfig.UI_MODE.TYPE_WATCH
        ConfigurationOuterClass.Configuration.UiModeType.UI_MODE_TYPE_VRHEADSET ->
            ResTableConfig.UI_MODE.TYPE_VR_HEADSET
        else -> ResTableConfig.UI_MODE.TYPE_ANY
    }

    val uiModeNight = when (config.uiModeNight) {
        ConfigurationOuterClass.Configuration.UiModeNight.UI_MODE_NIGHT_NIGHT ->
            ResTableConfig.UI_MODE.NIGHT_YES
        ConfigurationOuterClass.Configuration.UiModeNight.UI_MODE_NIGHT_NOTNIGHT ->
            ResTableConfig.UI_MODE.NIGHT_NO
        else -> ResTableConfig.UI_MODE.NIGHT_ANY
    }

    val density = config.density

    val touchscreen = when (config.touchscreen) {
        ConfigurationOuterClass.Configuration.Touchscreen.TOUCHSCREEN_NOTOUCH ->
            ResTableConfig.TOUCHSCREEN.NOTOUCH
        ConfigurationOuterClass.Configuration.Touchscreen.TOUCHSCREEN_STYLUS ->
            ResTableConfig.TOUCHSCREEN.STYLUS
        ConfigurationOuterClass.Configuration.Touchscreen.TOUCHSCREEN_FINGER ->
            ResTableConfig.TOUCHSCREEN.FINGER
        else -> ResTableConfig.TOUCHSCREEN.ANY
    }

    val keysHidden = when (config.keysHidden) {
        ConfigurationOuterClass.Configuration.KeysHidden.KEYS_HIDDEN_KEYSEXPOSED ->
            ResTableConfig.INPUT_FLAGS.KEYSHIDDEN_NO
        ConfigurationOuterClass.Configuration.KeysHidden.KEYS_HIDDEN_KEYSHIDDEN ->
            ResTableConfig.INPUT_FLAGS.KEYSHIDDEN_YES
        ConfigurationOuterClass.Configuration.KeysHidden.KEYS_HIDDEN_KEYSSOFT ->
            ResTableConfig.INPUT_FLAGS.KEYSHIDDEN_SOFT
        else -> ResTableConfig.INPUT_FLAGS.KEYSHIDDEN_ANY
    }

    val keyboard = when (config.keyboard) {
        ConfigurationOuterClass.Configuration.Keyboard.KEYBOARD_NOKEYS ->
            ResTableConfig.KEYBOARD.NOKEYS
        ConfigurationOuterClass.Configuration.Keyboard.KEYBOARD_QWERTY ->
            ResTableConfig.KEYBOARD.QWERTY
        ConfigurationOuterClass.Configuration.Keyboard.KEYBOARD_TWELVEKEY ->
            ResTableConfig.KEYBOARD.TWELVEKEY
        else -> ResTableConfig.KEYBOARD.ANY
    }

    val navHidden = when (config.navHidden) {
        ConfigurationOuterClass.Configuration.NavHidden.NAV_HIDDEN_NAVHIDDEN ->
            ResTableConfig.INPUT_FLAGS.NAVHIDDEN_YES
        ConfigurationOuterClass.Configuration.NavHidden.NAV_HIDDEN_NAVEXPOSED ->
            ResTableConfig.INPUT_FLAGS.NAVHIDDEN_NO
        else -> ResTableConfig.INPUT_FLAGS.NAVHIDDEN_ANY
    }

    val navigation = when (config.navigation) {
        ConfigurationOuterClass.Configuration.Navigation.NAVIGATION_NONAV ->
            ResTableConfig.NAVIGATION.NONAV
        ConfigurationOuterClass.Configuration.Navigation.NAVIGATION_DPAD ->
            ResTableConfig.NAVIGATION.DPAD
        ConfigurationOuterClass.Configuration.Navigation.NAVIGATION_TRACKBALL ->
            ResTableConfig.NAVIGATION.TRACKBALL
        ConfigurationOuterClass.Configuration.Navigation.NAVIGATION_WHEEL ->
            ResTableConfig.NAVIGATION.WHEEL
        else -> ResTableConfig.NAVIGATION.ANY
    }

    val screenWidth = config.screenWidth
    val screenHeight = config.screenHeight
    val sdkVersion = config.sdkVersion.toShort()

    val configDescription = ConfigDescription(
        ResTableConfig(
            mcc = mcc,
            mnc = mnc,
            orientation = orientation,
            touchscreen = touchscreen,
            density = density,
            keyboard = keyboard,
            navigation = navigation,
            inputFlags = (keysHidden.toInt() or navHidden.toInt()).toByte(),
            screenWidth = screenWidth,
            screenHeight = screenHeight,
            sdkVersion = sdkVersion,
            screenLayout = (screenLayoutDir.toInt() or
                    screenLayoutLong.toInt() or
                    screenLayoutSize.toInt()).toByte(),
            uiMode = (uiModeType.toInt() or uiModeNight.toInt()).toByte(),
            smallestScreenWidthDp = smallestScreenWidthDp,
            screenWidthDp = screenWidthDp,
            screenHeightDp = screenHeightDp,
            screenLayout2 = screenRound,
            colorMode = (wideGamutColor.toInt() or hdr.toInt()).toByte()))
    localeValue.writeTo(configDescription)

    return configDescription
}

fun deserializeFileTypeFromPb(type: Resources.FileReference.Type) = when (type) {
    Resources.FileReference.Type.BINARY_XML -> ResourceFile.Type.BinaryXml
    Resources.FileReference.Type.PROTO_XML -> ResourceFile.Type.ProtoXml
    Resources.FileReference.Type.PNG -> ResourceFile.Type.Png
    else -> ResourceFile.Type.Unknown
}

fun deserializeReferenceTypeFromPb(type: Resources.Reference.Type) = when (type) {
    Resources.Reference.Type.ATTRIBUTE -> Reference.Type.ATTRIBUTE
    else -> Reference.Type.RESOURCE
}

fun deserializeSourceFromPb(source: Resources.Source, sourcePool: ResStringPool): Source {
    val path = sourcePool.strings[source.pathIdx]
    val line = source.position.lineNumber
    return Source(path, line)
}

fun deserializeVisibilityFromPb(level: Resources.Visibility.Level) = when(level) {
    Resources.Visibility.Level.PRIVATE -> ResourceVisibility.PRIVATE
    Resources.Visibility.Level.PUBLIC -> ResourceVisibility.PUBLIC
    else -> ResourceVisibility.UNDEFINED
}

fun deserializeOverlayableFromPb(
    item: Resources.OverlayableItem,
    overlayable: Overlayable,
    sourcePool: ResStringPool,
    logger: ILogger?): OverlayableItem? {

    var policies = 0
    for (policy in item.policyList) {
        policies = policies or when (policy) {
            Resources.OverlayableItem.Policy.PUBLIC -> OverlayableItem.Policy.PUBLIC
            Resources.OverlayableItem.Policy.SYSTEM -> OverlayableItem.Policy.SYSTEM
            Resources.OverlayableItem.Policy.VENDOR -> OverlayableItem.Policy.VENDOR
            Resources.OverlayableItem.Policy.PRODUCT -> OverlayableItem.Policy.PRODUCT
            Resources.OverlayableItem.Policy.SIGNATURE -> OverlayableItem.Policy.SIGNATURE
            Resources.OverlayableItem.Policy.ODM -> OverlayableItem.Policy.ODM
            Resources.OverlayableItem.Policy.OEM -> OverlayableItem.Policy.OEM
            else -> {
                logger?.error(null, "Unrecognized policy: %s.", policy)
                return null
            }
        }
    }

    val source = if (item.hasSource()) {
        deserializeSourceFromPb(item.source, sourcePool)
    } else {
        Source.EMPTY
    }
    val comment = item.comment
    return OverlayableItem(overlayable, policies, comment, source)
}

fun deserializeBinPrimitiveFromPb(
    primitive: Resources.Primitive, logger: ILogger?): BinaryPrimitive? {

    val (type, deviceValue) = when(primitive.oneofValueCase) {
        Resources.Primitive.OneofValueCase.NULL_VALUE ->
            Pair(ResValue.DataType.NULL, ResValue.NullFormat.UNDEFINED)
        Resources.Primitive.OneofValueCase.EMPTY_VALUE ->
            Pair(ResValue.DataType.NULL, ResValue.NullFormat.EMPTY)
        Resources.Primitive.OneofValueCase.FLOAT_VALUE -> {
            val float = primitive.floatValue
            Pair(ResValue.DataType.FLOAT, float.toRawBits())
        }
        Resources.Primitive.OneofValueCase.DIMENSION_VALUE ->
            Pair(ResValue.DataType.DIMENSION, primitive.dimensionValue)
        Resources.Primitive.OneofValueCase.FRACTION_VALUE ->
            Pair(ResValue.DataType.FRACTION, primitive.fractionValue)
        Resources.Primitive.OneofValueCase.INT_DECIMAL_VALUE ->
            Pair(ResValue.DataType.INT_DEC, primitive.intDecimalValue)
        Resources.Primitive.OneofValueCase.INT_HEXADECIMAL_VALUE ->
            Pair(ResValue.DataType.INT_HEX, primitive.intHexadecimalValue)
        Resources.Primitive.OneofValueCase.BOOLEAN_VALUE -> {
            val boolValue = if (primitive.booleanValue) 0xffffffff.toInt() else 0
            Pair(ResValue.DataType.INT_BOOLEAN, boolValue)
        }
        Resources.Primitive.OneofValueCase.COLOR_ARGB8_VALUE ->
            Pair(ResValue.DataType.INT_COLOR_ARGB8, primitive.colorArgb8Value)
        Resources.Primitive.OneofValueCase.COLOR_RGB8_VALUE ->
            Pair(ResValue.DataType.INT_COLOR_RGB8, primitive.colorRgb8Value)
        Resources.Primitive.OneofValueCase.COLOR_ARGB4_VALUE ->
            Pair(ResValue.DataType.INT_COLOR_ARGB4, primitive.colorArgb4Value)
        Resources.Primitive.OneofValueCase.COLOR_RGB4_VALUE ->
            Pair(ResValue.DataType.INT_COLOR_RGB4, primitive.colorRgb4Value)
        Resources.Primitive.OneofValueCase.DIMENSION_VALUE_DEPRECATED -> {
            val float = primitive.dimensionValueDeprecated
            Pair(ResValue.DataType.DIMENSION, float.toRawBits())
        }
        Resources.Primitive.OneofValueCase.FRACTION_VALUE_DEPRECATED -> {
            val float = primitive.fractionValueDeprecated
            Pair(ResValue.DataType.FRACTION, float.toRawBits())
        }
        else -> {
            val errorMsg = "Value case unrecognized for Primitive proto: %s"
            logger?.error(null, errorMsg, primitive.oneofValueCase)
            return null
        }
    }
    return BinaryPrimitive(ResValue(type, deviceValue.deviceToHost()))
}

fun deserializeStringFromPb(string: Resources.String, valuePool: StringPool) =
    BasicString(valuePool.makeRef(string.value))

fun deserializeRawFromPb(string: Resources.RawString, valuePool: StringPool) =
    RawString(valuePool.makeRef(string.value))

fun deserializeStyledStrFromPb(
    string: Resources.StyledString, valuePool: StringPool): StyledString {

    val spans = mutableListOf<Span>()
    for (span in string.spanList) {
        spans.add(Span(span.tag, span.firstChar, span.lastChar))
    }
    return StyledString(valuePool.makeRef(StyleString(string.value, spans)), listOf())
}

fun deserializeReferenceFromPb(ref: Resources.Reference, logger: ILogger?): Reference? {
    val reference = if (ref.name.isNotEmpty()) {
        val refName = parseResourceName(ref.name)
        if (refName == null) {
            val errorMsg = "%s cannot be parsed as a Resource Name."
            logger?.error(null, errorMsg, ref.name)
            return null
        }
        Reference(refName.resourceName)
    } else {
        Reference()
    }

    reference.referenceType = deserializeReferenceTypeFromPb(ref.type)
    reference.isPrivate = ref.private

    if (ref.id != 0) {
        reference.id = ref.id
    }
    return reference
}

fun deserializeFileRefFromPb(
    file: Resources.FileReference, valuePool: StringPool, config: ConfigDescription): FileReference {

    val fileRef = FileReference(
        valuePool.makeRef(
            file.path,
            StringPool.Context(StringPool.Context.Priority.HIGH.priority, config)))
    fileRef.type = deserializeFileTypeFromPb(file.type)

    return fileRef
}

fun deserializeAttrFromPb(
    attr: Resources.Attribute, sourcePool: ResStringPool, logger: ILogger?): AttributeResource? {

    val attrResource = AttributeResource(attr.formatFlags)
    attrResource.minInt = attr.minInt
    attrResource.maxInt = attr.maxInt

    for (symbol in attr.symbolList) {
        val reference = deserializeReferenceFromPb(symbol.name, logger)
        reference ?: return null

        if (symbol.hasSource()) {
            reference.source = deserializeSourceFromPb(symbol.source, sourcePool)
        }
        reference.comment = symbol.comment

        attrResource.symbols.add(
            AttributeResource.Symbol(reference, symbol.value, symbol.type.toByte()))
    }

    return attrResource
}

fun deserializeStyleFromPb(
    style: Resources.Style,
    valuePool: StringPool,
    config: ConfigDescription,
    sourcePool: ResStringPool,
    logger: ILogger?): Style? {

    val styleResource = Style()
    if (style.hasParent()) {
        val parentRef = deserializeReferenceFromPb(style.parent, logger)
        parentRef ?: return null

        parentRef.source = if (style.hasParentSource()) {
            deserializeSourceFromPb(style.parentSource, sourcePool)
        } else {
            Source.EMPTY
        }
        styleResource.parent = parentRef
    }

    for (entry in style.entryList) {
        val key = deserializeReferenceFromPb(entry.key, logger)
        key ?: return null

        val value = deserializeItemFromPb(entry.item, valuePool, config, logger)
        value ?: return null
        if (entry.hasSource()) {
            key.source = deserializeSourceFromPb(entry.source, sourcePool)
            value.source = deserializeSourceFromPb(entry.source, sourcePool)
        }
        key.comment = entry.comment
        value.comment = entry.comment
        styleResource.entries.add(Style.Entry(key, value))
    }

    return styleResource
}

fun deserializeStyleableFromPb(
    styleable: Resources.Styleable, sourcePool: ResStringPool, logger: ILogger?): Styleable? {

    val styleableResource = Styleable()
    for (entry in styleable.entryList) {
        val reference = deserializeReferenceFromPb(entry.attr, logger)
        reference?: return null
        if (entry.hasSource()) {
            reference.source = deserializeSourceFromPb(entry.source, sourcePool)
        }
        reference.comment = entry.comment
        styleableResource.entries.add(reference)
    }
    return styleableResource
}

fun deserializeArrayFromPb(
    array: Resources.Array,
    valuePool: StringPool,
    config: ConfigDescription,
    sourcePool: ResStringPool,
    logger: ILogger?): ArrayResource? {

    val arrayResource = ArrayResource()
    for (entry in array.elementList) {
        val item = deserializeItemFromPb(entry.item, valuePool, config, logger)
        item ?: return null

        if (entry.hasSource()) {
            item.source = deserializeSourceFromPb(entry.source, sourcePool)
        }
        item.comment = entry.comment
        arrayResource.elements.add(item)
    }
    return arrayResource
}

fun deserializePluralTypeFromPb(type: Resources.Plural.Arity) = when (type) {
    Resources.Plural.Arity.ZERO -> Plural.Type.ZERO
    Resources.Plural.Arity.ONE -> Plural.Type.ONE
    Resources.Plural.Arity.TWO -> Plural.Type.TWO
    Resources.Plural.Arity.FEW -> Plural.Type.FEW
    Resources.Plural.Arity.MANY -> Plural.Type.MANY
    else -> Plural.Type.OTHER
}

fun deserializePluralFromPb(
    plural: Resources.Plural,
    valuePool: StringPool,
    config: ConfigDescription,
    sourcePool: ResStringPool,
    logger: ILogger?): Plural? {

    val pluralResource = Plural()
    for (entry in plural.entryList) {
        val type = deserializePluralTypeFromPb(entry.arity)
        val item = deserializeItemFromPb(entry.item, valuePool, config, logger)

        item ?: return null

        item.source = if (entry.hasSource()) {
            deserializeSourceFromPb(entry.source, sourcePool)
        } else {
            Source.EMPTY
        }
        item.comment = entry.comment

        pluralResource.setValue(type, item)
    }

    return pluralResource
}

fun deserializeMacroFromPb(
    pbMacro: Resources.MacroBody,
    valuePool: StringPool,
    config: ConfigDescription,
    sourcePool: ResStringPool,
    logger: ILogger?): Macro {

    val macro = Macro()
    macro.rawValue = pbMacro.rawString

    if (pbMacro.hasStyleString()) {
        val spans = ArrayList<Span>()
        for (span in pbMacro.styleString.spansList) {
            spans.add(Span(span.name, span.startIndex, span.endIndex))
        }
        macro.styleString = StyleString(pbMacro.styleString.str, spans)
    }

    val untranslatableList = ArrayList<UntranslatableSection>()
    for (untranslatable in pbMacro.untranslatableSectionsList) {
        untranslatableList.add(
            UntranslatableSection(
                untranslatable.startIndex.toInt(), untranslatable.endIndex.toInt())
        )
    }
    macro.untranslatables = untranslatableList

    val aliasNamespaces = ArrayList<Macro.Namespace>()
    for (namespace in pbMacro.namespaceStackList) {
        aliasNamespaces.add(
            Macro.Namespace(namespace.prefix, namespace.packageName, namespace.isPrivate)
        )
    }
    macro.aliasNamespaces = aliasNamespaces

    return macro
}

fun deserializeItemFromPb(
    item: Resources.Item,
    valuePool: StringPool,
    config: ConfigDescription,
    logger: ILogger?): Item? = when (item.valueCase) {

    Resources.Item.ValueCase.REF -> deserializeReferenceFromPb(item.ref, logger)
    Resources.Item.ValueCase.STR -> deserializeStringFromPb(item.str, valuePool)
    Resources.Item.ValueCase.RAW_STR -> deserializeRawFromPb(item.rawStr, valuePool)
    Resources.Item.ValueCase.STYLED_STR ->
        deserializeStyledStrFromPb(item.styledStr, valuePool)
    Resources.Item.ValueCase.FILE -> deserializeFileRefFromPb(item.file, valuePool, config)
    Resources.Item.ValueCase.ID -> Id()
    Resources.Item.ValueCase.PRIM -> deserializeBinPrimitiveFromPb(item.prim, logger)
    else -> {
        val errorMsg = "Unrecognized value case for Item: %s."
        logger?.error(null, errorMsg, item.valueCase)
        null
    }
}

fun deserializeValueFromPb(
    value: Resources.Value,
    valuePool: StringPool,
    config: ConfigDescription,
    sourcePool: ResStringPool,
    logger: ILogger?): Value? {

    val valueResource = when {
        value.hasItem() -> deserializeItemFromPb(value.item, valuePool, config, logger)
        value.hasCompoundValue() -> {
            val compoundValue = value.compoundValue

            when (compoundValue.valueCase) {
                Resources.CompoundValue.ValueCase.ATTR ->
                    deserializeAttrFromPb(compoundValue.attr, sourcePool, logger)
                Resources.CompoundValue.ValueCase.STYLE ->
                    deserializeStyleFromPb(compoundValue.style, valuePool, config, sourcePool, logger)
                Resources.CompoundValue.ValueCase.STYLEABLE ->
                    deserializeStyleableFromPb(compoundValue.styleable, sourcePool, logger)
                Resources.CompoundValue.ValueCase.ARRAY ->
                    deserializeArrayFromPb(compoundValue.array, valuePool, config, sourcePool, logger)
                Resources.CompoundValue.ValueCase.PLURAL ->
                    deserializePluralFromPb(compoundValue.plural, valuePool, config, sourcePool, logger)
                Resources.CompoundValue.ValueCase.MACRO ->
                    deserializeMacroFromPb(compoundValue.macro, valuePool, config, sourcePool, logger)
                else -> {
                    val errorMsg = "Unrecognized compoundValue value case %s"
                    logger?.error(null, errorMsg, compoundValue.valueCase)
                    null
                }
            }
        }
        else -> {
            val errorMsg = "Unrecognized case for serialized value %s"
            logger?.error(null, errorMsg, value.valueCase)
            null
        }
    }
    valueResource ?: return null

    valueResource.weak = value.weak
    if (value.hasSource()) {
        valueResource.source = deserializeSourceFromPb(value.source, sourcePool)
    }
    valueResource.comment = value.comment

    return valueResource
}

fun deserializePackageFromPb(
    original: Resources.Package,
    sourcePool: ResStringPool,
    overlayables: List<Overlayable>,
    logger: ILogger?,
    table: ResourceTable): Boolean {

    val packageId =
        if (original.hasPackageId()) original.packageId.id.toByte() else 0.toByte()

    val idIndex = mutableMapOf<Int, ResourceName>()

    val resourcePackage =
        table.createPackageAllowingDuplicateNames(original.packageName, packageId)
    for (group in original.typeList) {
        val resType = resourceTypeFromTag(group.name)

        if (resType == null) {
            val errorMsg = "Unrecognized Resource Type: %s."
            logger?.error(null, errorMsg, group.name)
            return false
        }

        val resourceGroup = resourcePackage.findOrCreateGroup(resType)
        if (group.hasTypeId()) {
            resourceGroup.id = group.typeId.id.toByte()
        }

        for (entry in group.entryList) {
            val resourceEntry = resourceGroup.findOrCreateEntry(entry.name)
            if (entry.hasEntryId()) {
                resourceEntry.id = entry.entryId.id.toShort()
            }

            // Deserialize the symbol status (public/private with metadata)
            if (entry.hasVisibility()) {
                val visibility = entry.visibility

                val visibilityLevel = deserializeVisibilityFromPb(visibility.level)
                when (visibilityLevel) {
                    // Propagate the visibility up to the type group.
                    ResourceVisibility.PUBLIC -> resourceGroup.visibility = ResourceVisibility.PUBLIC
                    // Propagate private, only if previously undefined.
                    ResourceVisibility.PRIVATE-> {
                        if (resourceGroup.visibility == ResourceVisibility.UNDEFINED) {
                            resourceGroup.visibility = ResourceVisibility.PRIVATE
                        }
                    }
                    else -> {}
                }

                val visibilitySource = if (visibility.hasSource()) {
                    deserializeSourceFromPb(visibility.source, sourcePool)
                } else {
                    Source.EMPTY
                }
                val visibilityComment = visibility.comment

                resourceEntry.visibility = Visibility(visibilitySource, visibilityComment, visibilityLevel)
            }

            if (entry.hasAllowNew()) {
                val allowNew = entry.allowNew
                val allowNewSource = if (allowNew.hasSource()) {
                    deserializeSourceFromPb(allowNew.source, sourcePool)
                } else {
                    Source.EMPTY
                }
                val allowNewComment = allowNew.comment
                resourceEntry.allowNew = AllowNew(allowNewSource, allowNewComment)
            }

            if (entry.hasOverlayableItem()) {
                // Find the overlayable to which this item belongs.
                val overlayableItem = entry.overlayableItem
                if (overlayableItem.overlayableIdx >= overlayables.size) {
                    val errorMsg = "Illegal value for overlayable id '%s'. Because only '%s' overlayables" +
                            "exist in proto."
                    logger?.error(null, errorMsg, overlayableItem.overlayableIdx, overlayables.size)
                    return false
                }
                val resourceOverlayable =
                    deserializeOverlayableFromPb(
                        overlayableItem,
                        overlayables[overlayableItem.overlayableIdx],
                        sourcePool,
                        logger)
                resourceOverlayable ?: return false

                resourceEntry.overlayable = resourceOverlayable
            }

            val resourceId = resourceIdFromParts(
                original.packageId.id.toByte(),
                group.typeId.id.toByte(),
                entry.entryId.id.toShort())
            if (resourceId.isValidId()) {
                idIndex[resourceId] =
                    ResourceName(resourcePackage.name, resourceGroup.type, resourceEntry.name)
            }

            for (configValue in entry.configValueList) {
                val config = configValue.config

                val resourceConfig = deserializeConfigFromPb(config, logger)
                resourceConfig ?: return false

                val resourceConfigValue =
                    resourceEntry.findOrCreateValue(resourceConfig, config.product)
                if (resourceConfigValue.value.isTruthy()) {
                    val errorMsg = "Value already exists for name %s, config %s, and product %s."
                    logger?.error(
                        null,
                        errorMsg,
                        ResourceName(resourcePackage.name, resourceGroup.type, resourceEntry.name),
                        resourceConfig,
                        config.product)
                    return false
                }

                resourceConfigValue.value =
                    deserializeValueFromPb(
                        configValue.value, table.stringPool, resourceConfig, sourcePool, logger)

                resourceConfigValue.value ?: return false
            }
        }
    }

    // Assign all references with ids the names of the resource to which they are referencing.
    for (group in resourcePackage.groups) {
        for (idToEntries in group.entries.values) {
            for (entry in idToEntries.values) {
                for (configValue in entry.values) {
                    val value = configValue.value
                    if (value is Reference) {
                        val referenceId = value.id
                        if (referenceId == null || !referenceId.isValidId()) {
                            continue
                        }
                        val result = idIndex[referenceId]
                        result ?: continue
                        value.name = result
                    }
                }
            }
        }
    }
    return true
}

fun deserializeTableFromPb(
    table: Resources.ResourceTable, outTable: ResourceTable, logger: ILogger?): Boolean {

    val sourcePool = ResStringPool.get(
        table.sourcePool.data.asReadOnlyByteBuffer(),
        table.sourcePool.serializedSize
    )

    // Deserialize the overlayable groups of a table
    val overlayables = mutableListOf<Overlayable>()
    for (overlayable in table.overlayableList) {
        val overlayableSource = if (overlayable.hasSource()) {
            deserializeSourceFromPb(overlayable.source, sourcePool)
        } else {
            Source.EMPTY
        }
        overlayables.add(Overlayable(overlayable.name, overlayable.actor, overlayableSource))
    }

    for (resourcePackage in table.packageList) {
        if (!deserializePackageFromPb(resourcePackage, sourcePool, overlayables, logger, outTable)) {
            return false
        }
    }
    return true
}