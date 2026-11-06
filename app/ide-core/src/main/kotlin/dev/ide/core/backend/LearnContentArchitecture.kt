package dev.ide.core.backend

// ===========================================================================
// Android: Architecture. ViewModels, UI state, repositories, navigation, Room
// and networking. The console scratch has no androidx on its classpath, so the
// graded exercises model each idea in plain Kotlin (+ kotlinx.coroutines for
// StateFlow); the real androidx APIs are shown as code samples.
// ===========================================================================

internal fun androidArchitecture(): LearnTrackDef = LearnTrackDef(
    id = "android-architecture",
    title = "Android: Architecture",
    subtitle = "ViewModels, state, repositories, navigation and data",
    iconId = "module.android",
    accentColor = ACCENT_ANDROID,
    language = "kotlin",
    category = "Android",
    level = "Advanced",
    prerequisites = listOf("compose-intro", "kotlin-coroutines"),
    lessons = listOf(
        archViewModel(),
        archRepository(),
        archCollect(),
        archNavigation(),
        archRoom(),
        archNetwork(),
        archCheckpoint(),
        archCapstone(),
    ),
)

private fun archViewModel() = LearnLessonDef(
    id = "arch-viewmodel", title = "ViewModel and UI state",
    summary = "Keep screen state in one place that survives rotation.",
    iconId = "module.android", estMinutes = 10,
    steps = listOf(
        LearnStepDef.Concept(
            "arch-vm-c", "Why a ViewModel?",
            listOf(
                text("When you rotate the phone, Android destroys your Activity and builds a new one. Anything you kept in the Activity is gone. A **ViewModel** lives outside that churn: it survives configuration changes and is cleared only when the screen is really finished."),
                text("A good ViewModel exposes its screen as **one immutable UI state**: a `data class` describing everything the screen shows. The UI reads it; it never edits it directly."),
                code(
                    """
                    data class CounterUiState(
                        val count: Int = 0,
                        val isLoading: Boolean = false,
                    )
                    """
                ),
                text("The state lives in a `StateFlow`: a flow that always holds a current `value` and tells collectors whenever it changes. Keep the writable `MutableStateFlow` private, and hand out a read-only `StateFlow`:"),
                code(
                    """
                    import androidx.lifecycle.ViewModel
                    import androidx.lifecycle.viewModelScope
                    import kotlinx.coroutines.flow.*
                    import kotlinx.coroutines.launch

                    class CounterViewModel : ViewModel() {
                        private val _uiState = MutableStateFlow(CounterUiState())
                        val uiState: StateFlow<CounterUiState> = _uiState.asStateFlow()

                        fun increment() {
                            _uiState.update { it.copy(count = it.count + 1) }
                        }

                        fun refresh() {
                            viewModelScope.launch {
                                _uiState.update { it.copy(isLoading = true) }
                                // ... load something ...
                                _uiState.update { it.copy(isLoading = false) }
                            }
                        }
                    }
                    """
                ),
                text("`update { }` takes the current state and returns a new one. Because the state is a data class, `copy(...)` builds that new value and changes only the fields you name."),
                tip("`viewModelScope` is cancelled automatically when the ViewModel is cleared, so work you launch there never outlives the screen."),
            ),
        ),
        LearnStepDef.Interactive(
            "arch-vm-i", "Build a CounterViewModel",
            listOf(
                text("The console has no androidx, so this `CounterViewModel` is a plain class. The idea is exactly the same."),
                text("Finish it:\n1. Add `private val _state = MutableStateFlow(CounterState())`.\n2. Make `state` expose it read-only (`_state.asStateFlow()`).\n3. Make `increment()` add one to `count` with `_state.update { it.copy(...) }`."),
                text("`main` is already written. When you are done it prints:"),
                code(
                    """
                    CounterState(count=0)
                    CounterState(count=2)
                    Count is 3
                    """,
                    "plain",
                ),
            ),
            starterCode = """
                import kotlinx.coroutines.flow.*

                data class CounterState(val count: Int = 0)

                class CounterViewModel {
                    // 1. Add a private MutableStateFlow named _state, starting at CounterState()
                    // 2. Expose it read-only as state (use asStateFlow())
                    val state: StateFlow<CounterState> = MutableStateFlow(CounterState())

                    fun increment() {
                        // 3. Add one to count with _state.update { it.copy(...) }
                    }
                }

                fun main() {
                    val viewModel = CounterViewModel()
                    println(viewModel.state.value)
                    viewModel.increment()
                    viewModel.increment()
                    println(viewModel.state.value)
                    viewModel.increment()
                    println("Count is ${'$'}{viewModel.state.value.count}")
                }
            """,
            language = "kotlin-coroutines",
            hints = listOf(
                "private val _state = MutableStateFlow(CounterState())",
                "val state: StateFlow<CounterState> = _state.asStateFlow()",
                "Inside increment(): _state.update { it.copy(count = it.count + 1) }",
            ),
            solution = """
                import kotlinx.coroutines.flow.*

                data class CounterState(val count: Int = 0)

                class CounterViewModel {
                    private val _state = MutableStateFlow(CounterState())
                    val state: StateFlow<CounterState> = _state.asStateFlow()

                    fun increment() {
                        _state.update { it.copy(count = it.count + 1) }
                    }
                }

                fun main() {
                    val viewModel = CounterViewModel()
                    println(viewModel.state.value)
                    viewModel.increment()
                    viewModel.increment()
                    println(viewModel.state.value)
                    viewModel.increment()
                    println("Count is ${'$'}{viewModel.state.value.count}")
                }
            """,
            check = ExerciseCheck(
                expectedOutput = "CounterState(count=0)\nCounterState(count=2)\nCount is 3",
                requireSource = listOf("private val _state", "MutableStateFlow", "_state.update"),
            ),
        ),
        LearnStepDef.Quiz(
            "arch-vm-q", "Quick check",
            prompt = "Why does the ViewModel keep MutableStateFlow private and expose a StateFlow?",
            options = listOf(
                "StateFlow is faster than MutableStateFlow",
                "So only the ViewModel can change the state; the UI can only read it",
                "Because a MutableStateFlow cannot be collected",
                "It is required for the app to compile",
            ),
            correctIndex = 1,
            explanation = "The UI sends events (like increment()) and reads state. Only the ViewModel writes it, so every change goes through one place.",
        ),
    ),
)

private fun archRepository() = LearnLessonDef(
    id = "arch-repository", title = "Repositories",
    summary = "Hide where your data comes from behind one class.",
    iconId = "module.android", estMinutes = 10,
    steps = listOf(
        LearnStepDef.Concept(
            "arch-repo-c", "A single source of truth",
            listOf(
                text("Your ViewModel should not care whether notes come from a database, the network or memory. It talks to a **repository**, and the repository decides."),
                code(
                    """
                    UI  ->  ViewModel  ->  Repository  ->  Room / network / memory
                    """,
                    "plain",
                ),
                text("Describe the repository as an **interface**. The app uses a real implementation; tests and previews use a fake one that keeps data in a list:"),
                code(
                    """
                    interface NotesRepository {
                        fun add(note: String)
                        fun all(): List<String>
                        fun delete(note: String): Boolean
                    }

                    class NotesViewModel(private val repository: NotesRepository) {
                        fun addNote(text: String) = repository.add(text)
                    }
                    """
                ),
                tip("The repository is the **single source of truth**: every screen asks it, so two screens can never disagree about the data."),
            ),
        ),
        LearnStepDef.Interactive(
            "arch-repo-i", "Write a FakeNotesRepository",
            listOf(
                text("`main` reads commands and calls the repository. Implement `FakeNotesRepository` so it really works:\n- `add` remembers a note.\n- `all()` returns every note **sorted alphabetically**.\n- `delete` removes a note and returns `true`, or returns `false` if it was not there."),
                text("The first input line is how many commands follow. For this input:"),
                code(
                    """
                    5
                    add Milk
                    add Eggs
                    list
                    delete Milk
                    list
                    """,
                    "plain",
                ),
                text("the program prints:"),
                code(
                    """
                    Added Milk
                    Added Eggs
                    Notes: Eggs, Milk
                    Deleted Milk
                    Notes: Eggs
                    """,
                    "plain",
                ),
                tip("This exercise runs with several different inputs, so the repository has to really keep track of the notes."),
            ),
            starterCode = """
                interface NotesRepository {
                    fun add(note: String)
                    fun all(): List<String>
                    fun delete(note: String): Boolean
                }

                class FakeNotesRepository : NotesRepository {
                    // Keep the notes in a private mutable list

                    override fun add(note: String) {
                        // TODO: remember the note
                    }

                    override fun all(): List<String> {
                        // TODO: return every note, sorted alphabetically
                        return emptyList()
                    }

                    override fun delete(note: String): Boolean {
                        // TODO: remove the note; return true if it was there
                        return false
                    }
                }

                fun main() {
                    val repository: NotesRepository = FakeNotesRepository()
                    val count = readln().trim().toInt()
                    repeat(count) {
                        val line = readln().trim()
                        val command = line.substringBefore(" ")
                        val argument = line.substringAfter(" ", "")
                        when (command) {
                            "add" -> {
                                repository.add(argument)
                                println("Added ${'$'}argument")
                            }
                            "delete" -> {
                                if (repository.delete(argument)) println("Deleted ${'$'}argument")
                                else println("Not found: ${'$'}argument")
                            }
                            "list" -> {
                                val notes = repository.all()
                                println(if (notes.isEmpty()) "Notes: none" else "Notes: " + notes.joinToString(", "))
                            }
                        }
                    }
                }
            """,
            language = "kotlin",
            hints = listOf(
                "Add a field: private val notes = mutableListOf<String>()",
                "all() can return notes.sorted(), which leaves the original list alone.",
                "MutableList.remove(note) already returns true when it removed something.",
            ),
            solution = """
                interface NotesRepository {
                    fun add(note: String)
                    fun all(): List<String>
                    fun delete(note: String): Boolean
                }

                class FakeNotesRepository : NotesRepository {
                    private val notes = mutableListOf<String>()

                    override fun add(note: String) {
                        notes.add(note)
                    }

                    override fun all(): List<String> = notes.sorted()

                    override fun delete(note: String): Boolean = notes.remove(note)
                }

                fun main() {
                    val repository: NotesRepository = FakeNotesRepository()
                    val count = readln().trim().toInt()
                    repeat(count) {
                        val line = readln().trim()
                        val command = line.substringBefore(" ")
                        val argument = line.substringAfter(" ", "")
                        when (command) {
                            "add" -> {
                                repository.add(argument)
                                println("Added ${'$'}argument")
                            }
                            "delete" -> {
                                if (repository.delete(argument)) println("Deleted ${'$'}argument")
                                else println("Not found: ${'$'}argument")
                            }
                            "list" -> {
                                val notes = repository.all()
                                println(if (notes.isEmpty()) "Notes: none" else "Notes: " + notes.joinToString(", "))
                            }
                        }
                    }
                }
            """,
            check = ExerciseCheck(
                requireSource = listOf("class FakeNotesRepository : NotesRepository", "override fun all"),
                cases = listOf(
                    ExerciseCase(
                        "5\nadd Milk\nadd Eggs\nlist\ndelete Milk\nlist\n",
                        "Added Milk\nAdded Eggs\nNotes: Eggs, Milk\nDeleted Milk\nNotes: Eggs",
                    ),
                    ExerciseCase(
                        "4\nlist\nadd Bread\ndelete Butter\nlist\n",
                        "Notes: none\nAdded Bread\nNot found: Butter\nNotes: Bread",
                    ),
                    ExerciseCase(
                        "6\nadd Tea\nadd Apples\nadd Coffee\ndelete Tea\ndelete Tea\nlist\n",
                        "Added Tea\nAdded Apples\nAdded Coffee\nDeleted Tea\nNot found: Tea\nNotes: Apples, Coffee",
                    ),
                ),
            ),
        ),
        LearnStepDef.Quiz(
            "arch-repo-q", "Quick check",
            prompt = "Why does the ViewModel depend on the NotesRepository interface instead of one concrete class?",
            options = listOf(
                "Interfaces run faster",
                "Kotlin does not allow a class as a constructor parameter",
                "You can hand it a fake in tests and previews, and a real Room or network one in the app",
                "So the ViewModel can change the data source by itself",
            ),
            correctIndex = 2,
            explanation = "Depending on the interface keeps the ViewModel ignorant of where data lives, so swapping the implementation needs no change to it.",
        ),
    ),
)

private fun archCollect() = LearnLessonDef(
    id = "arch-collect", title = "Collecting state in Compose",
    summary = "Turn a StateFlow into a screen that redraws itself.",
    iconId = "module.android", estMinutes = 8,
    steps = listOf(
        LearnStepDef.Concept(
            "arch-collect-c", "From StateFlow to UI",
            listOf(
                text("In a composable, you turn the ViewModel's `StateFlow` into Compose state. Each new value recomposes the screen:"),
                code(
                    """
                    @Composable
                    fun CounterRoute(viewModel: CounterViewModel = viewModel()) {
                        val state by viewModel.uiState.collectAsStateWithLifecycle()
                        CounterScreen(state = state, onIncrement = viewModel::increment)
                    }

                    @Composable
                    fun CounterScreen(state: CounterUiState, onIncrement: () -> Unit) {
                        // draws `state`, reports clicks through onIncrement
                    }
                    """
                ),
                text("Notice the split. `CounterRoute` knows about the ViewModel. `CounterScreen` only takes a state and a callback: state flows **down**, events flow **up**. This is called **unidirectional data flow**."),
                text("The preview below has no ViewModel (previews cannot create one), so a remembered state stands in for `collectAsStateWithLifecycle()`. The screen itself is exactly what your app would use. Tap the button, or edit the code:"),
                composePreview(
                    """
                    import androidx.compose.foundation.layout.Column
                    import androidx.compose.foundation.layout.padding
                    import androidx.compose.material3.Button
                    import androidx.compose.material3.MaterialTheme
                    import androidx.compose.material3.Text
                    import androidx.compose.runtime.Composable
                    import androidx.compose.runtime.getValue
                    import androidx.compose.runtime.mutableStateOf
                    import androidx.compose.runtime.remember
                    import androidx.compose.runtime.setValue
                    import androidx.compose.ui.Modifier
                    import androidx.compose.ui.tooling.preview.Preview
                    import androidx.compose.ui.unit.dp

                    data class CounterUiState(val count: Int = 0, val message: String = "Tap the button")

                    @Composable
                    fun CounterScreen(state: CounterUiState, onIncrement: () -> Unit) {
                        Column(modifier = Modifier.padding(16.dp)) {
                            Text("Count: " + state.count, style = MaterialTheme.typography.headlineSmall)
                            Text(state.message)
                            Button(onClick = onIncrement) {
                                Text("Add one")
                            }
                        }
                    }

                    @Composable
                    fun CounterRoute() {
                        // In your app: val state by viewModel.uiState.collectAsStateWithLifecycle()
                        var state by remember { mutableStateOf(CounterUiState()) }
                        CounterScreen(
                            state = state,
                            onIncrement = { state = state.copy(count = state.count + 1, message = "Nice, keep going") },
                        )
                    }

                    @Preview
                    @Composable
                    fun CounterRoutePreview() {
                        CounterRoute()
                    }
                    """,
                    interactive = true,
                    caption = "State down, events up",
                ),
                text("Because `CounterScreen` is stateless, you can preview it in any state you like, with no ViewModel at all:"),
                composePreview(
                    """
                    import androidx.compose.foundation.layout.Column
                    import androidx.compose.foundation.layout.padding
                    import androidx.compose.material3.MaterialTheme
                    import androidx.compose.material3.Text
                    import androidx.compose.runtime.Composable
                    import androidx.compose.ui.Modifier
                    import androidx.compose.ui.tooling.preview.Preview
                    import androidx.compose.ui.unit.dp

                    data class ScoreState(val count: Int, val message: String)

                    @Composable
                    fun ScoreScreen(state: ScoreState) {
                        Column(modifier = Modifier.padding(16.dp)) {
                            Text("Count: " + state.count, style = MaterialTheme.typography.headlineSmall)
                            Text(state.message)
                        }
                    }

                    @Preview
                    @Composable
                    fun ScoreScreenBusyPreview() {
                        ScoreScreen(ScoreState(99, "Almost at 100!"))
                    }
                    """,
                    caption = "A fixed state, no ViewModel needed",
                ),
                note("`collectAsStateWithLifecycle()` comes from `androidx.lifecycle:lifecycle-runtime-compose`. Plain `collectAsState()` is built into Compose."),
            ),
        ),
        LearnStepDef.Quiz(
            "arch-collect-q", "Quick check",
            prompt = "What does collectAsStateWithLifecycle() do that collectAsState() does not?",
            options = listOf(
                "It stops collecting while the app is in the background, and resumes when it is visible again",
                "It saves the state to disk",
                "It makes the StateFlow writable from the UI",
                "Nothing, they are two names for the same function",
            ),
            correctIndex = 0,
            explanation = "collectAsState() keeps collecting as long as the composable is in composition. The lifecycle-aware version pauses below STARTED, so a hidden app does no wasted work.",
        ),
    ),
)

private fun archNavigation() = LearnLessonDef(
    id = "arch-navigation", title = "Navigation",
    summary = "Move between screens with Navigation Compose.",
    iconId = "module.android", estMinutes = 8,
    steps = listOf(
        LearnStepDef.Concept(
            "arch-nav-c", "NavHost and routes",
            listOf(
                text("**Navigation Compose** maps **routes** to screens. A `NavController` keeps a **back stack**: `navigate` pushes a screen on top, and Back pops it off."),
                code(
                    """
                    @Composable
                    fun AppNavHost() {
                        val navController = rememberNavController()
                        NavHost(navController = navController, startDestination = "home") {
                            composable("home") {
                                HomeScreen(onOpenNote = { id -> navController.navigate("detail/${'$'}id") })
                            }
                            composable(
                                route = "detail/{noteId}",
                                arguments = listOf(navArgument("noteId") { type = NavType.IntType }),
                            ) { entry ->
                                val noteId = entry.arguments?.getInt("noteId") ?: 0
                                DetailScreen(noteId = noteId, onBack = { navController.popBackStack() })
                            }
                        }
                    }
                    """
                ),
                text("Tapping note 42 calls `navController.navigate(\"detail/42\")`. The `{noteId}` part of the route becomes an argument the detail screen reads."),
                text("Newer versions of Navigation also support **type-safe routes**, where a route is a `@Serializable` class instead of a string:"),
                code(
                    """
                    @Serializable object Home
                    @Serializable data class Detail(val noteId: Int)

                    composable<Detail> { entry ->
                        val detail: Detail = entry.toRoute()
                        DetailScreen(noteId = detail.noteId)
                    }

                    navController.navigate(Detail(noteId = 42))
                    """
                ),
                tip("Pass **ids**, not whole objects. The detail screen (or its ViewModel) loads the note from the repository, so it always shows the latest data, even after Android recreates the app."),
                note("Navigation needs the `androidx.navigation:navigation-compose` dependency in a real project."),
            ),
        ),
        LearnStepDef.Quiz(
            "arch-nav-q1", "Back stack",
            prompt = "You go from home to detail/42, then to detail/7. You press Back once. Where are you?",
            options = listOf("home", "detail/7", "detail/42", "The app closes"),
            correctIndex = 2,
            explanation = "Each navigate() pushes onto the back stack. Back pops the top entry (detail/7), revealing detail/42 underneath.",
        ),
        LearnStepDef.Quiz(
            "arch-nav-q2", "Passing data",
            prompt = "Why pass a note's id to the detail screen instead of the whole Note object?",
            options = listOf(
                "Ids are the only type a route can hold",
                "An id is small and survives the app being recreated; the screen loads the latest note from the repository",
                "Objects make navigation slower to animate",
                "So the detail screen cannot show the note",
            ),
            correctIndex = 1,
            explanation = "The repository stays the single source of truth. The route only says which note, never a copy of it that could go stale.",
        ),
    ),
)

private fun archRoom() = LearnLessonDef(
    id = "arch-room", title = "Saving data with Room",
    summary = "Entities, DAOs and a database that outlives the app.",
    iconId = "module.android", estMinutes = 9,
    steps = listOf(
        LearnStepDef.Concept(
            "arch-room-c", "Entity, Dao, Database",
            listOf(
                text("**Room** stores your data in a SQLite database on the device. You describe it with three pieces."),
                text("An **Entity** is a table. Each property is a column:"),
                code(
                    """
                    @Entity(tableName = "notes")
                    data class NoteEntity(
                        @PrimaryKey(autoGenerate = true) val id: Int = 0,
                        val text: String,
                        val createdAt: Long,
                    )
                    """
                ),
                text("A **Dao** (data access object) lists what you can do with the table. Writes are `suspend` functions, so they never block the main thread. A query that returns a `Flow` emits a fresh list every time the table changes:"),
                code(
                    """
                    @Dao
                    interface NoteDao {
                        @Query("SELECT * FROM notes ORDER BY createdAt DESC")
                        fun observeAll(): Flow<List<NoteEntity>>

                        @Insert
                        suspend fun insert(note: NoteEntity)

                        @Delete
                        suspend fun delete(note: NoteEntity)
                    }
                    """
                ),
                text("The **Database** ties them together. You build it once and share it:"),
                code(
                    """
                    @Database(entities = [NoteEntity::class], version = 1)
                    abstract class AppDatabase : RoomDatabase() {
                        abstract fun noteDao(): NoteDao
                    }

                    val db = Room.databaseBuilder(context, AppDatabase::class.java, "app.db").build()
                    """
                ),
                text("Your repository wraps the Dao, and the ViewModel turns its Flow into UI state:"),
                code(
                    """
                    class NotesRepository(private val dao: NoteDao) {
                        val notes: Flow<List<NoteEntity>> = dao.observeAll()
                        suspend fun add(text: String) = dao.insert(NoteEntity(text = text, createdAt = System.currentTimeMillis()))
                    }
                    """
                ),
                note("Room generates the Dao code at build time, so a real project adds `androidx.room:room-runtime`, `room-ktx` and the `room-compiler` annotation processor through **KSP**."),
            ),
        ),
        LearnStepDef.Quiz(
            "arch-room-q1", "Flow queries",
            prompt = "A Dao function returns Flow<List<NoteEntity>>. What happens when you insert a new note?",
            options = listOf(
                "Nothing until you call the query again",
                "The app must restart to see it",
                "The insert fails because the Flow is still open",
                "The Flow emits a new list that includes the note, so collectors update automatically",
            ),
            correctIndex = 3,
            explanation = "Room watches the tables a query reads and re-runs it on every change. Collect that Flow in the ViewModel and the screen stays in sync for free.",
        ),
        LearnStepDef.Quiz(
            "arch-room-q2", "suspend",
            prompt = "Why are insert and delete marked suspend in the Dao?",
            options = listOf(
                "Database work can be slow, so Room runs it off the main thread without blocking the UI",
                "suspend functions are stored in a different table",
                "Room only accepts functions that return nothing",
                "It makes the database encrypted",
            ),
            correctIndex = 0,
            explanation = "Disk I/O on the main thread freezes the UI. A suspend Dao function lets you call it from a coroutine (like viewModelScope.launch) safely.",
        ),
    ),
)

private fun archNetwork() = LearnLessonDef(
    id = "arch-network", title = "Talking to the network",
    summary = "Fetch data with Retrofit and model loading, success and error.",
    iconId = "module.android", estMinutes = 10,
    steps = listOf(
        LearnStepDef.Concept(
            "arch-net-c", "Retrofit and suspend",
            listOf(
                text("**Retrofit** turns an interface into HTTP calls. Mark the function `suspend` and call it from a coroutine:"),
                code(
                    """
                    @Serializable
                    data class Repo(
                        val id: Long,
                        val name: String,
                        @SerialName("stargazers_count") val stars: Int,
                    )

                    interface GitHubApi {
                        @GET("users/{user}/repos")
                        suspend fun repos(@Path("user") user: String): List<Repo>
                    }

                    val api: GitHubApi = Retrofit.Builder()
                        .baseUrl("https://api.github.com/")
                        .addConverterFactory(Json.asConverterFactory("application/json".toMediaType()))
                        .build()
                        .create(GitHubApi::class.java)
                    """
                ),
                text("The converter (here kotlinx.serialization; Gson or Moshi work too) turns JSON into your data classes."),
                text("Networks fail: no signal, a timeout, a server error. Catch that in the repository and turn it into a **state** the UI can show, instead of a crash:"),
                code(
                    """
                    sealed interface LoadState {
                        data object Loading : LoadState
                        data class Success(val items: List<String>) : LoadState
                        data class Error(val message: String) : LoadState
                    }

                    class RepoRepository(private val api: GitHubApi) {
                        suspend fun load(user: String): LoadState = try {
                            LoadState.Success(api.repos(user).map { it.name })
                        } catch (e: IOException) {
                            LoadState.Error("Check your connection")
                        } catch (e: HttpException) {
                            LoadState.Error("Server error ${'$'}{e.code()}")
                        }
                    }
                    """
                ),
                note("Your app also needs `<uses-permission android:name=\"android.permission.INTERNET\" />` in its manifest, or every request fails."),
            ),
        ),
        LearnStepDef.Interactive(
            "arch-net-i", "Render a LoadState",
            listOf(
                text("Model a screen that loads a list:\n1. Add `Success(val items: List<String>)` and `Error(val message: String)` to the sealed interface.\n2. Give `render` a branch for each state, with no `else`.\n3. Uncomment the three states in `main`."),
                text("`render` should return:\n- `Loading...` for Loading\n- `Showing 2: Kotlin, Compose` for a Success with items (the count, then the items joined with `, `)\n- `Nothing here yet` for a Success with no items\n- `Error: No internet` for an Error (`Error: ` then the message)"),
                text("The finished program prints:"),
                code(
                    """
                    Loading...
                    Showing 2: Kotlin, Compose
                    Nothing here yet
                    Error: No internet
                    """,
                    "plain",
                ),
                tip("With a sealed interface, `when` knows every possible state. Leave out `else`, and the compiler tells you if you forget one."),
            ),
            starterCode = """
                sealed interface LoadState {
                    data object Loading : LoadState
                    // TODO: data class Success(val items: List<String>) : LoadState
                    // TODO: data class Error(val message: String) : LoadState
                }

                fun render(state: LoadState): String = when (state) {
                    LoadState.Loading -> "Loading..."
                    // TODO: a branch for Success and one for Error, then remove else
                    else -> "TODO"
                }

                fun main() {
                    val states = listOf<LoadState>(
                        LoadState.Loading,
                        // LoadState.Success(listOf("Kotlin", "Compose")),
                        // LoadState.Success(emptyList()),
                        // LoadState.Error("No internet"),
                    )
                    for (state in states) {
                        println(render(state))
                    }
                }
            """,
            language = "kotlin",
            hints = listOf(
                "Inside the interface: data class Success(val items: List<String>) : LoadState",
                "Branch with is LoadState.Success -> ... and is LoadState.Error -> ...",
                "For Success, check state.items.isEmpty() first, then build the text with state.items.size and state.items.joinToString(\", \").",
            ),
            solution = """
                sealed interface LoadState {
                    data object Loading : LoadState
                    data class Success(val items: List<String>) : LoadState
                    data class Error(val message: String) : LoadState
                }

                fun render(state: LoadState): String = when (state) {
                    LoadState.Loading -> "Loading..."
                    is LoadState.Success ->
                        if (state.items.isEmpty()) "Nothing here yet"
                        else "Showing ${'$'}{state.items.size}: ${'$'}{state.items.joinToString(", ")}"
                    is LoadState.Error -> "Error: ${'$'}{state.message}"
                }

                fun main() {
                    val states = listOf<LoadState>(
                        LoadState.Loading,
                        LoadState.Success(listOf("Kotlin", "Compose")),
                        LoadState.Success(emptyList()),
                        LoadState.Error("No internet"),
                    )
                    for (state in states) {
                        println(render(state))
                    }
                }
            """,
            check = ExerciseCheck(
                expectedOutput = "Loading...\nShowing 2: Kotlin, Compose\nNothing here yet\nError: No internet",
                requireSource = listOf("sealed interface LoadState", "class Success", "class Error", "when (state)"),
            ),
        ),
        LearnStepDef.Quiz(
            "arch-net-q", "Quick check",
            prompt = "Where should a failed network call be turned into something the user sees?",
            options = listOf(
                "Nowhere: let the exception crash the app so you notice it",
                "In the repository or ViewModel, by catching it and producing an Error state the UI renders",
                "In the AndroidManifest",
                "Inside every composable that draws text",
            ),
            correctIndex = 1,
            explanation = "Catch where the call is made and map it to state. The UI just renders Loading, Success or Error, and never deals with exceptions.",
        ),
    ),
)

private fun archCheckpoint() = LearnLessonDef(
    id = "arch-checkpoint", title = "Android: Architecture review", kind = "checkpoint",
    summary = "Five questions across the whole track.",
    iconId = "check", estMinutes = 5,
    steps = listOf(
        LearnStepDef.Quiz(
            "arch-cp-q1", "ViewModel",
            prompt = "Why keep a screen's state in a ViewModel instead of the Activity?",
            options = listOf(
                "A ViewModel draws the UI faster",
                "The ViewModel survives configuration changes like rotation, the Activity does not",
                "Activities cannot hold variables",
                "A ViewModel saves the state to the cloud",
            ),
            correctIndex = 1,
            explanation = "Rotation recreates the Activity. The ViewModel is kept, so the state is still there for the new Activity.",
        ),
        LearnStepDef.Quiz(
            "arch-cp-q2", "UI state",
            prompt = "Which is the recommended shape for a screen's UI state?",
            options = listOf(
                "Many separate mutable variables in the composable",
                "A global object every screen writes to",
                "One immutable data class, exposed as a StateFlow and replaced with copy() on each change",
                "A string the UI parses",
            ),
            correctIndex = 2,
            explanation = "One immutable value describes the whole screen. Changes produce a new value, so the UI can never see a half-updated state.",
        ),
        LearnStepDef.Quiz(
            "arch-cp-q3", "Data flow",
            prompt = "In unidirectional data flow, how does a button tap change what is on screen?",
            options = listOf(
                "The composable calls a ViewModel function; the ViewModel updates the state; the new state flows back down",
                "The composable edits the StateFlow value directly",
                "The button redraws itself with new text",
                "The Activity restarts with the new value",
            ),
            correctIndex = 0,
            explanation = "Events go up, state comes down. Every change passes through the ViewModel, which keeps the logic in one testable place.",
        ),
        LearnStepDef.Quiz(
            "arch-cp-q4", "Repositories",
            prompt = "What is a repository's job?",
            options = listOf(
                "To draw lists on screen",
                "To store the git history of the app",
                "To replace the ViewModel",
                "To be the single source of truth, hiding whether data comes from Room, the network or memory",
            ),
            correctIndex = 3,
            explanation = "The ViewModel asks the repository and does not care where the data really lives. That also makes it easy to swap in a fake for tests.",
        ),
        LearnStepDef.Quiz(
            "arch-cp-q5", "Room",
            prompt = "Your Dao exposes fun observeAll(): Flow<List<NoteEntity>>. What does the screen gain by collecting it?",
            options = listOf(
                "It updates automatically whenever a note is added, changed or deleted",
                "Notes load without a database",
                "The list is sorted for free",
                "Nothing, a plain List would be the same",
            ),
            correctIndex = 0,
            explanation = "Room re-emits the query whenever its table changes, so the UI stays in sync without you asking again.",
        ),
    ),
)

private fun archCapstone() = LearnLessonDef(
    id = "arch-capstone", title = "Todo app core", kind = "capstone",
    summary = "A repository and a ViewModel with StateFlow, driven like a real screen.",
    iconId = "module.android", estMinutes = 15,
    steps = listOf(
        LearnStepDef.Concept(
            "arch-cap-c", "Putting it together",
            listOf(
                text("Time to build the core of a todo app, the part that would sit behind a Compose screen:"),
                text("- A `TodoRepository` that owns the list of todos.\n- A `TodoViewModel` that exposes `StateFlow<TodoUiState>` and handles three events: add, toggle and set a filter.\n- `main` plays the role of the UI: it sends events and prints what the screen would show."),
                code(
                    """
                    UI (main)  --add / toggle / setFilter-->  TodoViewModel  -->  TodoRepository
                    UI (main)  <------- StateFlow<TodoUiState> -------  TodoViewModel
                    """,
                    "plain",
                ),
                tip("Write a private `refresh()` in the ViewModel that rebuilds the state from the repository and the current filter. Call it after every event."),
            ),
        ),
        LearnStepDef.Interactive(
            "arch-cap-i", "Todo app core",
            listOf(
                text("Finish `TodoRepository.toggle` and the `TodoViewModel`:\n- `add(title)` adds through the repository.\n- `toggle(id)` flips that todo's `done`.\n- `setFilter(filter)` changes which todos are visible: `ALL`, `ACTIVE` (not done) or `DONE`.\n- After every event, `state` holds the visible todos, the filter, and `remaining` (how many are not done, whatever the filter)."),
                text("`main` and `printState` are written for you. The finished program prints:"),
                code(
                    """
                    Added three (3 left)
                    [ ] 1 Buy milk
                    [ ] 2 Write code
                    [ ] 3 Walk dog
                    Toggled 2 (2 left)
                    [ ] 1 Buy milk
                    [x] 2 Write code
                    [ ] 3 Walk dog
                    Active only (2 left)
                    [ ] 1 Buy milk
                    [ ] 3 Walk dog
                    Done only (2 left)
                    [x] 2 Write code
                    Undid 2 (3 left)
                    (nothing to show)
                    """,
                    "plain",
                ),
            ),
            starterCode = """
                import kotlinx.coroutines.flow.*

                data class Todo(val id: Int, val title: String, val done: Boolean = false)

                enum class Filter { ALL, ACTIVE, DONE }

                data class TodoUiState(
                    val visible: List<Todo> = emptyList(),
                    val filter: Filter = Filter.ALL,
                    val remaining: Int = 0,
                )

                class TodoRepository {
                    private val todos = mutableListOf<Todo>()
                    private var nextId = 1

                    fun add(title: String) {
                        todos.add(Todo(nextId++, title))
                    }

                    fun toggle(id: Int) {
                        // TODO: flip done on the todo with this id (data classes have copy())
                    }

                    fun all(): List<Todo> = todos.toList()
                }

                class TodoViewModel(private val repository: TodoRepository) {
                    // TODO: a private MutableStateFlow(TodoUiState()) named _state,
                    // exposed read-only as state
                    val state: StateFlow<TodoUiState> = MutableStateFlow(TodoUiState())

                    fun add(title: String) {
                        // TODO: add through the repository, then refresh the state
                    }

                    fun toggle(id: Int) {
                        // TODO: toggle through the repository, then refresh the state
                    }

                    fun setFilter(filter: Filter) {
                        // TODO: remember the filter in the state, then refresh it
                    }

                    // TODO: a private refresh() that rebuilds visible and remaining
                    // from repository.all() and the current filter
                }

                fun printState(label: String, state: TodoUiState) {
                    println("${'$'}label (${'$'}{state.remaining} left)")
                    if (state.visible.isEmpty()) println("(nothing to show)")
                    for (todo in state.visible) {
                        val box = if (todo.done) "[x]" else "[ ]"
                        println("${'$'}box ${'$'}{todo.id} ${'$'}{todo.title}")
                    }
                }

                fun main() {
                    val viewModel = TodoViewModel(TodoRepository())
                    viewModel.add("Buy milk")
                    viewModel.add("Write code")
                    viewModel.add("Walk dog")
                    printState("Added three", viewModel.state.value)
                    viewModel.toggle(2)
                    printState("Toggled 2", viewModel.state.value)
                    viewModel.setFilter(Filter.ACTIVE)
                    printState("Active only", viewModel.state.value)
                    viewModel.setFilter(Filter.DONE)
                    printState("Done only", viewModel.state.value)
                    viewModel.toggle(2)
                    printState("Undid 2", viewModel.state.value)
                }
            """,
            language = "kotlin-coroutines",
            hints = listOf(
                "toggle: find the index with todos.indexOfFirst { it.id == id }, then replace that item with a copy(done = !done).",
                "In the ViewModel: private val _state = MutableStateFlow(TodoUiState()) and val state: StateFlow<TodoUiState> = _state.asStateFlow()",
                "setFilter: _state.update { it.copy(filter = filter) }, then call refresh().",
                "refresh: read repository.all(), pick the visible ones with a when on the current filter, and count the todos that are not done for remaining.",
            ),
            solution = """
                import kotlinx.coroutines.flow.*

                data class Todo(val id: Int, val title: String, val done: Boolean = false)

                enum class Filter { ALL, ACTIVE, DONE }

                data class TodoUiState(
                    val visible: List<Todo> = emptyList(),
                    val filter: Filter = Filter.ALL,
                    val remaining: Int = 0,
                )

                class TodoRepository {
                    private val todos = mutableListOf<Todo>()
                    private var nextId = 1

                    fun add(title: String) {
                        todos.add(Todo(nextId++, title))
                    }

                    fun toggle(id: Int) {
                        val index = todos.indexOfFirst { it.id == id }
                        if (index >= 0) {
                            todos[index] = todos[index].copy(done = !todos[index].done)
                        }
                    }

                    fun all(): List<Todo> = todos.toList()
                }

                class TodoViewModel(private val repository: TodoRepository) {
                    private val _state = MutableStateFlow(TodoUiState())
                    val state: StateFlow<TodoUiState> = _state.asStateFlow()

                    fun add(title: String) {
                        repository.add(title)
                        refresh()
                    }

                    fun toggle(id: Int) {
                        repository.toggle(id)
                        refresh()
                    }

                    fun setFilter(filter: Filter) {
                        _state.update { it.copy(filter = filter) }
                        refresh()
                    }

                    private fun refresh() {
                        val all = repository.all()
                        _state.update { current ->
                            val visible = when (current.filter) {
                                Filter.ALL -> all
                                Filter.ACTIVE -> all.filter { !it.done }
                                Filter.DONE -> all.filter { it.done }
                            }
                            current.copy(visible = visible, remaining = all.count { !it.done })
                        }
                    }
                }

                fun printState(label: String, state: TodoUiState) {
                    println("${'$'}label (${'$'}{state.remaining} left)")
                    if (state.visible.isEmpty()) println("(nothing to show)")
                    for (todo in state.visible) {
                        val box = if (todo.done) "[x]" else "[ ]"
                        println("${'$'}box ${'$'}{todo.id} ${'$'}{todo.title}")
                    }
                }

                fun main() {
                    val viewModel = TodoViewModel(TodoRepository())
                    viewModel.add("Buy milk")
                    viewModel.add("Write code")
                    viewModel.add("Walk dog")
                    printState("Added three", viewModel.state.value)
                    viewModel.toggle(2)
                    printState("Toggled 2", viewModel.state.value)
                    viewModel.setFilter(Filter.ACTIVE)
                    printState("Active only", viewModel.state.value)
                    viewModel.setFilter(Filter.DONE)
                    printState("Done only", viewModel.state.value)
                    viewModel.toggle(2)
                    printState("Undid 2", viewModel.state.value)
                }
            """,
            check = ExerciseCheck(
                expectedOutput = """
                    Added three (3 left)
                    [ ] 1 Buy milk
                    [ ] 2 Write code
                    [ ] 3 Walk dog
                    Toggled 2 (2 left)
                    [ ] 1 Buy milk
                    [x] 2 Write code
                    [ ] 3 Walk dog
                    Active only (2 left)
                    [ ] 1 Buy milk
                    [ ] 3 Walk dog
                    Done only (2 left)
                    [x] 2 Write code
                    Undid 2 (3 left)
                    (nothing to show)
                """.trimIndent(),
                requireSource = listOf("MutableStateFlow", "StateFlow<TodoUiState>"),
            ),
        ),
    ),
)
