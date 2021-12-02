package com.tyron.kotlin_completion.index

import android.util.Log
import com.tyron.kotlin_completion.util.PsiUtils
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.kotlin.descriptors.DeclarationDescriptor
import org.jetbrains.kotlin.descriptors.ModuleDescriptor
import org.jetbrains.kotlin.name.FqName
import org.jetbrains.kotlin.resolve.scopes.DescriptorKindFilter
import org.jetbrains.kotlin.resolve.scopes.MemberScope
import java.lang.IllegalStateException
import java.time.Duration
import java.time.Instant
import kotlin.sequences.Sequence

private const val MAX_FQNAME_LENGTH = 255
private const val MAX_SHORT_NAME_LENGTH = 80

private object Symbols : Table() {
    val fqName = varchar("fqname", length = MAX_FQNAME_LENGTH) references FqNames.fqName
    val kind = integer("kind")
    val visibility = integer("visibility")
    val extensionReceiverType = varchar("extensionreceivertype", length = MAX_FQNAME_LENGTH).nullable()

    override val primaryKey = PrimaryKey(fqName)
}

private object FqNames : Table() {
    val fqName = varchar("fqname", length = MAX_FQNAME_LENGTH)
    val shortName = varchar("shortname", length = MAX_SHORT_NAME_LENGTH)

    override val primaryKey = PrimaryKey(fqName)
}
class SymbolIndex {
    private val db = Database.connect("jdbc:h2:mem:symbolindex;DB_CLOSE_DELAY=-1", "org.h2.Driver")

    var indexing: Boolean = false

    init {
       transaction (db) {
           SchemaUtils.create(Symbols, FqNames)
       }
    }

    fun refresh(module: ModuleDescriptor, forced: Boolean = true) {
        val started = System.currentTimeMillis()
        Log.d("SymbolIndex", "Updating symbol index...");

        indexing = true
        try {
            transaction(db) {
                if (forced) {
                    Symbols.deleteAll()
                }
                for (descriptor in allDescriptors(module)) {
                    val descriptorFqn = PsiUtils.getFqNameSafe(descriptor)
                    val extensionReceiverFqn = descriptor.accept(ExtractSymbolExtensionReceiverType, Unit)

                    if (canStoreFqName(descriptorFqn) && (extensionReceiverFqn?.let { canStoreFqName(it) } != false)) {

                        for (fqn in listOfNotNull(descriptorFqn, extensionReceiverFqn)) {
                            FqNames.replace {
                                it[fqName] = fqn.toString()
                                it[shortName] = fqn.shortName().toString()
                            }
                        }


                        Symbols.replace {
                            it[fqName] = descriptorFqn.toString()
                            it[kind] = descriptor.accept(ExtractSymbolKind, Unit).rawValue
                            it[visibility] = descriptor.accept(ExtractSymbolVisibility, Unit).rawValue
                            it[extensionReceiverType] = extensionReceiverFqn?.toString()
                        }
                    } else {
                        Log.w("SymbolIndex",
                            "Excluding symbol $descriptorFqn from index since its name is too long"
                        );
                    }
                }

                indexing = false
            }
        } catch (e: Exception) {
            Log.e("SymbolIndex", "Error while updating symbol index", e);
        }
    }

    fun query(prefix: String, receiverType: FqName? = null, limit: Int = 20): List<Symbol> {
        val start = Instant.now()
        try {
            return transaction(db) {
                (Symbols innerJoin FqNames)
                    .select { FqNames.shortName.like("$prefix%") and (Symbols.extensionReceiverType eq receiverType?.toString()) }
                    .limit(limit)
                    .map {
                        Symbol(
                            fqName = FqName(it[Symbols.fqName]),
                            kind = Symbol.Kind.fromRaw(it[Symbols.kind]),
                            visibility = Symbol.Visibility.fromRaw(it[Symbols.visibility]),
                            extensionReceiverType = it[Symbols.extensionReceiverType]?.let(::FqName)
                        )
                    }
            }
        } finally {
            Log.d("SymbolIndex", "Query took " + Duration.between(start, Instant.now()).toMillis() + " ms")
        }
    }

    private fun canStoreFqName(fqName: FqName) =
        fqName.toString().length <= MAX_FQNAME_LENGTH
                && fqName.shortName().toString().length <= MAX_SHORT_NAME_LENGTH

    private fun allDescriptors(module: ModuleDescriptor) : Sequence<DeclarationDescriptor> = allPackages(module)
        .map(module::getPackage)
        .flatMap {
            try {
                it.memberScope.getContributedDescriptors(DescriptorKindFilter.ALL, MemberScope.ALL_NAME_FILTER)
            } catch (e: IllegalStateException) {
                Log.w("SymbolIndex", "Couldn't query descriptors in package $it")
                emptyList()
            }
        }

    private fun allPackages(module: ModuleDescriptor, pkgName: FqName = FqName.ROOT) : Sequence<FqName> = module
        .getSubPackagesOf(pkgName) { it.toString()  != "META-INF"}
        .asSequence()
        .flatMap { sequenceOf(it) + allPackages(module, it) }
}