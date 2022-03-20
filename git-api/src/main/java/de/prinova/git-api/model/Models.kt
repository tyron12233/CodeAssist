package de.prinova.git.model

import org.eclipse.jgit.lib.PersonIdent

data class Author(val name: String, val email: String)

fun Author.of() = PersonIdent(name, email)