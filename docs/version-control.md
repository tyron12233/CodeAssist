# Version control

CodeAssist tracks projects with Git on device. The working copy, the branches, the history, and the GitHub
account a remote authenticates with are all handled in-process: there is no `git` binary on Android, and the
engine never shells out to one.

Three modules make it up, following the repo's api / impl / ui split:

| Module | What it is |
|---|---|
| `vcs-api` | The provider-neutral SPI: the repository/branch/commit/status model, `VcsRepository`, the `VcsProvider` extension point, and the account, credential, and forge ports. |
| `vcs-impl` | The engine: a [JGit](https://www.eclipse.org/jgit/)-backed working copy, the GitHub REST and device-flow client, and the encrypted account store. |
| `vcs-ui` | The Compose UI, contributed as one `UiPlugin`: the Git tool window plus the screens it navigates to. |

The engine facet (`VcsPlugin`) and the UI facet (`VcsUiPlugin`) are declared as one `BuiltInPlugin`, so the
whole feature is turned off from **Settings > Plugins** with a single toggle and contributes neither a service
nor a panel when disabled.

## The model

`VcsRepository` is the whole working-copy surface, and every method is blocking:

- **read** — `status()`, `branches()`, `remotes()`, `log()`, `commitDetail()`, `diff()`, `show()`, `stashes()`
- **working tree** — `stage()`, `unstage()`, `discard()`, `markResolved()`
- **history** — `commit()` (with amend)
- **branches** — `createBranch()`, `checkout()`, `deleteBranch()`, `renameBranch()`, `merge()`, `abortMerge()`
- **stash** — `stashPush()`, `stashApply()`, `stashDrop()`
- **network** — `fetch()`, `pull()`, `push()`
- **config** — `identity()`, `setIdentity()`, `ignore()`

Failures arrive as `VcsException` with a message already fit to show, or `VcsAuthException` when a remote
refused the credentials, which is what lets the UI offer sign-in instead of a transport error.

Paths in the model are repository-relative with `/` separators, the form Git itself records, so they are
stable across hosts and safe to persist.

### Providers

`VCS_PROVIDER_EP` (`platform.vcsProvider`) is the seam for a version-control system other than Git. The host
asks each registered provider whether a directory is a checkout it owns and uses the first that answers; the
built-in Git provider is the fallback when nothing is registered, because it needs a config directory that is
only known once a project manager exists.

## Running JGit on device

JGit is pure Java and its 6.10.x line targets Java 11 bytecode with no `StackWalker`, `VarHandle`, `ClassValue`,
or record references, so it dexes and runs on ART unchanged. Two things do need arranging:

- **Configuration lookup.** Out of the box JGit resolves the user config from `$HOME/.gitconfig` and discovers
  the system config by running `git config --system`. On a phone `user.home` is `/`, which is not writable, and
  there is no `git` binary, so every read pays a failed process spawn and every write fails. `GitEnvironment`
  installs a `SystemReader` that resolves the user, system, and JGit-own configs under an app-owned directory,
  which removes both.
- **Serialization.** JGit's commands are not safe for concurrent use on one repository, and the UI can easily
  fire a status refresh while a push is in flight, so `VcsBackend` holds a single mutex around every
  repository call.

## Accounts and credentials

Signing in is a GitHub concern, not a Git one: a checkout works with no account, and an account is useful
before any checkout exists. `ForgeClient` covers that half (sign-in, repositories, pull requests) and
`GitHubClient` implements it over OkHttp.

Two sign-in routes:

1. **The device-authorization grant.** The only browser flow that works without a client secret or a redirect
   the app can receive: the user opens a short URL, types a code, and the app polls until it is approved. The
   build ships its own client id (`GitHubClient.DEFAULT_CLIENT_ID`); a fork can substitute one under
   **Settings > Version Control > GitHub OAuth client id** (advanced).
2. **A personal access token.** Always available, and the fallback when no client id is configured. Needs the
   `repo` scope.

The client id must belong to an **OAuth App** with device flow enabled, not a GitHub App. A GitHub App ignores
the requested scopes, issues an eight-hour token that needs refreshing, only sees repositories it is installed
on, and cannot create one for a user account; `GitHubClient.validateClientId` rejects such an id up front
rather than letting sign-in appear to work and then degrade.

The two OAuth endpoints live on the web host rather than the REST API and answer
`application/x-www-form-urlencoded` unless the request asks for `application/json` by that exact name, so they
carry their own Accept header and the response reader falls back to parsing form fields.

Accounts live outside any project, under the app's shared directory, so a project archive never carries a
token. Metadata sits in `accounts.properties`; secrets sit in `credentials.properties` with each value
encrypted (AES-GCM) under a random key generated on first use and kept beside them with owner-only
permissions. That protects the files, not the process: anything running as the app can read the key, and on
Android the app-private storage boundary is what actually separates apps.

A remote's credentials are chosen by matching the URL's host against the signed-in accounts (an account on
`api.github.com` serves a `github.com` remote), falling back to a username and password saved for that host,
then to anonymous access.

## The UI

The Git tool window is a `LEFT`-anchored panel: the branch and sync header, the working-tree changes grouped
into conflicts, staged, and unstaged, and the commit box. It registers under the shell's source-control slot
(`LeftPanelId.SOURCE`), so it takes that rail position and the phone bottom-nav slot that maps to it. The shell
carries no placeholder of its own for that slot: with the plugin disabled there is simply no source-control
panel and the bottom-nav entry is hidden.

The panel is deliberately worded rather than drawn. Git's vocabulary is the thing newcomers trip on, so:

- Every icon-only control carries a tooltip (hover on desktop, long-press on touch).
- Each group of files states what it is: "Ready to commit", "Edited but not included yet", "Both sides
  changed these".
- Pull and push are labelled buttons with their ahead/behind counts, not two cloud arrows.
- Occasional actions (fetch, stashes, `.gitignore`, abort merge, discard, file history) are worded menu rows
  rather than more glyphs, since a menu row can afford a verb and an object and an icon cannot.
- Committing is one button whose label follows a "Push after committing" checkbox, so there is never a
  labelled Commit sitting next to an unlabelled second button that does something more.

Everything that needs room is a contributed screen the panel navigates to rather than a nested sheet, because
a sidebar is far too narrow for a branch list, a commit history, or a diff on a phone:

| Screen id | What it does |
|---|---|
| `vcs.branches` | Search, create, switch, merge into the current branch, delete. |
| `vcs.history` | The commit log; a commit expands to its files, and a file opens its diff. |
| `vcs.diff` | A unified diff, coloured by line kind. |
| `vcs.accounts` | Sign-in, the commit identity, and credentials for other Git servers. |
| `vcs.clone` | Clone from a URL or from the signed-in account's repositories. |
| `vcs.stashes` | Stash the working tree under a description, then apply or drop what is on the stack. |
| `vcs.github` | Publish a project as a new repository, list and open pull requests, manage remotes. |

Contributed screens are addressed by id alone, with no route to carry parameters on. Since exactly one screen
is visible at a time, the opener writes what the screen needs into `VcsNav` and navigates; the screen reads it
on entry.
