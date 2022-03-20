package de.prinova.git.model

import org.eclipse.jgit.lib.PersonIdent
import org.eclipse.jgit.api.Git

data class Author(val name: String, val email: String)
fun Author.of() = PersonIdent(name, email)

data class Gitter(val git: Git)