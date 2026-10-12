package dev.ide.lang.completion

/**
 * Which members code actually reaches for on the common Android, JDK and Kotlin types, most used first.
 *
 * A wide type lists hundreds of members (a `TextView` has more than 600 with `View`'s), and nothing in the
 * declarations says that `setText` and `setOnClickListener` matter more than `setAutoSizeTextTypeWithDefaults`.
 * Android Studio learns that from usage; this is the hand-curated equivalent, so a brand-new project already
 * gets the useful members first. Backends fill [CompletionRelevance.popularity] from [of] and the
 * [PopularityWeigher] ranks by it.
 *
 * Lookups walk the receiver's supertypes nearest first and score a member by the most specific list that
 * mentions it, so the lists interleave: on a `TextView`, `setText` (TextView's first) ties with
 * `setOnClickListener` (View's first). A subclass list may re-rank members it inherits (the `EditText` list leads
 * with `getText`), but only as a deliberate set: naming `setVisibility` there without `getVisibility` would
 * score the pair apart. Names are the Java member names; a Kotlin property (`text`) is scored as its
 * accessors (`getText` / `setText`).
 */
object MemberPopularity {

    /** The score of the first entry in a list; each later entry scores one less. */
    private const val TOP = 100

    /**
     * How commonly [name] is used on a receiver whose type and supertypes are [receiverChain] (the receiver's
     * own FQN first, then its supertypes nearest first); 0 when no list mentions it.
     */
    fun of(receiverChain: List<String>, name: String): Int {
        if (name.isEmpty()) return 0
        for (fqn in receiverChain) {
            val ranks = TABLE[fqn] ?: continue
            val best = forms(name).maxOf { ranks[it]?.let { index -> (TOP - index).coerceAtLeast(1) } ?: 0 }
            if (best > 0) return best
        }
        return 0
    }

    /** [name] plus, for a Kotlin property, the Java accessors it stands for (`text` -> `getText`, `setText`;
     *  `isEnabled` -> `setEnabled`). */
    private fun forms(name: String): List<String> {
        val first = name[0]
        if (!first.isLowerCase()) return listOf(name)
        if (name.length > 2 && name.startsWith("is") && name[2].isUpperCase()) return listOf(name, "set" + name.substring(2))
        val cap = first.uppercaseChar() + name.substring(1)
        return listOf(name, "get$cap", "set$cap")
    }

    private val TABLE: Map<String, Map<String, Int>> by lazy {
        LISTS.mapValues { (_, names) ->
            val ranks = HashMap<String, Int>()
            names.split(' ').filter { it.isNotEmpty() }.forEachIndexed { i, n -> if (n !in ranks) ranks[n] = i }
            ranks
        }
    }

    private val LISTS: Map<String, String> = mapOf(
        // ---- Android views ----
        "android.view.View" to "setOnClickListener setVisibility getVisibility findViewById setEnabled isEnabled " +
            "getContext setBackgroundColor setBackgroundResource setOnLongClickListener setAlpha setTag getTag post " +
            "postDelayed getId setPadding getLayoutParams setLayoutParams requestFocus invalidate getWidth getHeight " +
            "setSelected setClickable setBackground animate setOnTouchListener getParent setFocusable isShown " +
            "getResources setElevation setTranslationX setTranslationY setScaleX setScaleY setRotation " +
            "startAnimation clearFocus performClick isSelected setId getX getY setX setY",
        "android.widget.TextView" to "setText getText setTextColor setTextSize setTypeface setGravity setHint addTextChangedListener setMaxLines setSingleLine setEllipsize " +
            "setTextAppearance setCompoundDrawablesWithIntrinsicBounds setAllCaps setLines setMovementMethod " +
            "setInputType setError length setLineSpacing setHintTextColor setLinkTextColor setTextIsSelectable " +
            "getLineCount setCompoundDrawablePadding getCurrentTextColor getTextSize",
        "android.widget.EditText" to "getText setText setSelection setError addTextChangedListener setHint " +
            "setInputType selectAll setOnEditorActionListener setTextColor",
        "android.widget.ImageView" to "setImageResource setImageDrawable setImageBitmap setScaleType setColorFilter " +
            "setImageURI setImageTintList setAdjustViewBounds getDrawable",
        "android.widget.CompoundButton" to "isChecked setChecked setOnCheckedChangeListener toggle setText setEnabled",
        "android.widget.RadioGroup" to "setOnCheckedChangeListener getCheckedRadioButtonId check clearCheck",
        "android.widget.ProgressBar" to "setProgress getProgress setMax setIndeterminate getMax " +
            "incrementProgressBy setProgressTintList",
        "android.widget.SeekBar" to "setOnSeekBarChangeListener setProgress getProgress setMax getMax",
        "android.view.ViewGroup" to "addView removeView removeAllViews getChildCount getChildAt indexOfChild " +
            "removeViewAt",
        "android.widget.LinearLayout" to "setOrientation setGravity",
        "android.widget.AdapterView" to "setAdapter setOnItemClickListener getAdapter setOnItemSelectedListener " +
            "getSelectedItem setSelection getSelectedItemPosition setOnItemLongClickListener getItemAtPosition",
        "android.widget.ListView" to "setAdapter setOnItemClickListener getAdapter setDivider " +
            "setOnItemLongClickListener smoothScrollToPosition",
        "android.widget.ScrollView" to "scrollTo smoothScrollTo fullScroll fling addView",
        "android.widget.VideoView" to "setVideoURI setVideoPath start pause stopPlayback setMediaController " +
            "setOnPreparedListener setOnCompletionListener isPlaying seekTo",
        "android.webkit.WebView" to "loadUrl getSettings setWebViewClient setWebChromeClient loadData " +
            "loadDataWithBaseURL canGoBack goBack reload evaluateJavascript addJavascriptInterface getUrl stopLoading",
        "android.webkit.WebSettings" to "setJavaScriptEnabled setDomStorageEnabled setSupportZoom " +
            "setBuiltInZoomControls setLoadWithOverviewMode setUseWideViewPort setAllowFileAccess",
        "androidx.recyclerview.widget.RecyclerView" to "setAdapter setLayoutManager getAdapter addItemDecoration " +
            "scrollToPosition smoothScrollToPosition setHasFixedSize addOnScrollListener getLayoutManager " +
            "setItemAnimator",
        "androidx.recyclerview.widget.RecyclerView.Adapter" to "notifyDataSetChanged notifyItemInserted " +
            "notifyItemRemoved notifyItemChanged notifyItemRangeChanged notifyItemRangeInserted getItemCount " +
            "notifyItemRangeRemoved notifyItemMoved",
        "androidx.viewpager2.widget.ViewPager2" to "setAdapter setCurrentItem getCurrentItem " +
            "registerOnPageChangeCallback setOrientation setOffscreenPageLimit setUserInputEnabled",
        "com.google.android.material.floatingactionbutton.FloatingActionButton" to "show hide",
        "com.google.android.material.snackbar.Snackbar" to "show setAction make dismiss setActionTextColor setDuration",
        "android.view.Menu" to "findItem add clear size getItem",
        "android.view.MenuItem" to "getItemId setVisible setTitle setIcon setEnabled setChecked isChecked",
        "android.view.MotionEvent" to "getAction getX getY getRawX getRawY getActionMasked getPointerCount",
        "android.view.Window" to "setStatusBarColor addFlags clearFlags getDecorView setFlags " +
            "setNavigationBarColor setSoftInputMode setBackgroundDrawable setLayout getAttributes",
        "android.view.LayoutInflater" to "inflate from",

        // ---- Android app components ----
        "android.content.Context" to "getString startActivity getResources getSystemService " +
            "getSharedPreferences getApplicationContext getPackageName getColor getDrawable startService " +
            "sendBroadcast registerReceiver unregisterReceiver getFilesDir getCacheDir checkSelfPermission " +
            "getContentResolver getPackageManager openFileOutput openFileInput getAssets getExternalFilesDir " +
            "getMainLooper stopService bindService unbindService getTheme",
        "android.app.Activity" to "setContentView findViewById finish startActivity getIntent getString " +
            "runOnUiThread getWindow startActivityForResult onBackPressed setResult setTitle getLayoutInflater " +
            "requestPermissions getApplicationContext getResources getSystemService getSharedPreferences " +
            "invalidateOptionsMenu isFinishing recreate getApplication setRequestedOrientation " +
            "overridePendingTransition getPackageName",
        "androidx.fragment.app.FragmentActivity" to "setContentView findViewById getSupportFragmentManager finish " +
            "startActivity getIntent",
        "androidx.appcompat.app.AppCompatActivity" to "setContentView findViewById getSupportActionBar " +
            "setSupportActionBar getSupportFragmentManager finish startActivity getIntent",
        "androidx.fragment.app.Fragment" to "requireContext getActivity requireActivity getArguments " +
            "setArguments getView getContext requireView getViewLifecycleOwner getParentFragmentManager " +
            "getChildFragmentManager getString startActivity isAdded getResources",
        "androidx.fragment.app.FragmentManager" to "beginTransaction findFragmentById findFragmentByTag " +
            "popBackStack getFragments getBackStackEntryCount",
        "androidx.fragment.app.FragmentTransaction" to "replace add commit addToBackStack remove " +
            "setCustomAnimations commitNow show hide setReorderingAllowed",
        "android.app.Dialog" to "show dismiss setContentView setCancelable findViewById setTitle getWindow " +
            "isShowing setOnDismissListener cancel setCanceledOnTouchOutside",
        "android.app.AlertDialog.Builder" to "setTitle setMessage setPositiveButton setNegativeButton show create " +
            "setView setCancelable setItems setNeutralButton setIcon setOnDismissListener setSingleChoiceItems " +
            "setMultiChoiceItems",
        "androidx.appcompat.app.AlertDialog.Builder" to "setTitle setMessage setPositiveButton setNegativeButton " +
            "show create setView setCancelable setItems setNeutralButton setIcon setOnDismissListener " +
            "setSingleChoiceItems setMultiChoiceItems",
        "android.widget.Toast" to "show makeText setText setDuration setGravity cancel",
        "android.util.Log" to "d e i w v wtf",
        "android.os.Handler" to "post postDelayed removeCallbacks sendMessage sendEmptyMessage " +
            "removeCallbacksAndMessages obtainMessage sendMessageDelayed",
        "android.content.Intent" to "putExtra getStringExtra getIntExtra getBooleanExtra setAction getAction " +
            "setFlags addFlags getExtras setData getData setClass setType getSerializableExtra " +
            "getParcelableExtra hasExtra setPackage putExtras getLongExtra addCategory",
        "android.os.Bundle" to "putString getString putInt getInt putBoolean getBoolean containsKey " +
            "putSerializable getSerializable putParcelable getParcelable putLong getLong keySet remove isEmpty",
        "android.os.BaseBundle" to "putString getString putInt getInt putBoolean getBoolean containsKey " +
            "putLong getLong keySet remove isEmpty",
        "android.content.SharedPreferences" to "getString getInt getBoolean edit getLong getFloat contains " +
            "getAll registerOnSharedPreferenceChangeListener getStringSet",
        "android.content.SharedPreferences.Editor" to "putString putInt putBoolean apply commit remove clear " +
            "putLong putFloat putStringSet",
        "android.content.res.Resources" to "getString getColor getDrawable getDimension getDimensionPixelSize " +
            "getDisplayMetrics getIdentifier getStringArray getConfiguration getInteger getBoolean",
        "android.net.Uri" to "toString getPath getLastPathSegment getScheme getHost getQueryParameter buildUpon " +
            "parse fromFile",
        "android.database.Cursor" to "moveToFirst moveToNext getString getInt getColumnIndex close getCount " +
            "getLong getColumnIndexOrThrow isAfterLast moveToPosition getDouble getBlob isNull",
        "android.database.sqlite.SQLiteDatabase" to "execSQL rawQuery insert query update delete close " +
            "beginTransaction setTransactionSuccessful endTransaction isOpen",
        "android.media.MediaPlayer" to "start pause stop release setDataSource prepare prepareAsync isPlaying " +
            "setLooping seekTo getDuration getCurrentPosition setOnCompletionListener setVolume reset " +
            "setOnPreparedListener create",
        "android.graphics.Canvas" to "drawRect drawCircle drawLine drawText drawBitmap drawPath drawColor save " +
            "restore translate rotate scale drawRoundRect drawOval drawArc clipRect getWidth getHeight drawPaint " +
            "drawPoint",
        "android.graphics.Paint" to "setColor setStrokeWidth setStyle setTextSize setAntiAlias setAlpha " +
            "setTypeface measureText setShader setStrokeCap setTextAlign getColor",
        "android.graphics.Bitmap" to "getWidth getHeight compress recycle getPixel setPixel copy isRecycled " +
            "getConfig createScaledBitmap createBitmap",
        "android.app.NotificationManager" to "notify cancel createNotificationChannel cancelAll",
        "androidx.core.app.NotificationCompat.Builder" to "setContentTitle setContentText setSmallIcon build " +
            "setPriority setAutoCancel setContentIntent setStyle setOngoing setLargeIcon addAction setChannelId",
        "android.location.Location" to "getLatitude getLongitude getAccuracy getAltitude getSpeed getTime",
        "androidx.lifecycle.LiveData" to "observe getValue observeForever removeObserver hasObservers",
        "androidx.lifecycle.MutableLiveData" to "setValue postValue getValue observe observeForever",

        // ---- JDK ----
        "java.lang.String" to "length equals substring contains isEmpty trim split replace toLowerCase " +
            "toUpperCase indexOf charAt startsWith endsWith equalsIgnoreCase format valueOf toCharArray matches " +
            "lastIndexOf replaceAll compareTo getBytes isBlank concat join chars strip repeat lines",
        "java.lang.StringBuilder" to "append toString length insert reverse setLength deleteCharAt delete " +
            "charAt indexOf setCharAt replace",
        "java.util.Collection" to "add size isEmpty remove contains clear addAll forEach stream iterator " +
            "toArray removeIf containsAll",
        "java.util.List" to "add get size isEmpty remove contains clear addAll indexOf set forEach stream " +
            "iterator sort toArray removeIf subList containsAll",
        "java.util.Map" to "put get containsKey remove keySet values entrySet size isEmpty getOrDefault putAll " +
            "clear forEach containsValue putIfAbsent computeIfAbsent merge",
        "java.util.Set" to "add contains remove size isEmpty clear addAll iterator forEach stream",
        "java.util.Iterator" to "hasNext next remove",
        "java.io.File" to "exists getName getAbsolutePath getPath mkdirs delete listFiles isDirectory isFile " +
            "getParentFile length createNewFile getParent renameTo lastModified canRead canWrite list mkdir " +
            "toPath readText writeText",

        // ---- Kotlin ----
        "kotlin.String" to "length isEmpty isNotEmpty isBlank isNotBlank substring contains trim split replace " +
            "lowercase uppercase startsWith endsWith toInt toIntOrNull toDouble indexOf equals first last take " +
            "drop format toCharArray lines padStart removePrefix removeSuffix substringBefore substringAfter " +
            "ifEmpty toRegex repeat reversed count",
        "kotlin.collections.Iterable" to "forEach map filter first firstOrNull joinToString any all find " +
            "count sumOf mapNotNull forEachIndexed groupBy associateBy distinct sortedBy sorted reversed toList " +
            "toMutableList last lastOrNull take drop flatMap none",
        "kotlin.collections.Collection" to "size isEmpty isNotEmpty contains forEach map filter first " +
            "firstOrNull joinToString any all find count sumOf",
        "kotlin.collections.List" to "size get isEmpty isNotEmpty forEach map filter first firstOrNull last " +
            "lastOrNull indexOf contains getOrNull joinToString find any all count sumOf sortedBy " +
            "toMutableList subList indices lastIndex",
        "kotlin.collections.MutableList" to "add get size remove clear addAll isEmpty isNotEmpty set removeAt " +
            "forEach map filter indexOf contains first last removeIf sortBy",
        "kotlin.collections.Map" to "get containsKey keys values entries size isEmpty getOrDefault getOrElse " +
            "forEach filter map toMap isNotEmpty getValue mapValues filterKeys containsValue",
        "kotlin.collections.MutableMap" to "put get getOrPut remove containsKey keys values entries size clear " +
            "putAll set isEmpty forEach",
        "kotlin.collections.Set" to "contains size isEmpty isNotEmpty forEach map filter first toList",
        "kotlin.collections.MutableSet" to "add remove contains size clear addAll isEmpty forEach",

        // ---- Compose ----
        "androidx.compose.ui.Modifier" to "padding fillMaxWidth fillMaxSize size clickable background height " +
            "width align weight wrapContentSize clip border offset fillMaxHeight verticalScroll horizontalScroll " +
            "alpha shadow testTag",
    )
}
