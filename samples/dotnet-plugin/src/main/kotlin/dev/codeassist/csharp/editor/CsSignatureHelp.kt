package dev.codeassist.csharp.editor


import android.util.Log
import dev.codeassist.csharp.completion.CsDocText
import dev.codeassist.csharp.completion.CsSemanticCompletion
import dev.codeassist.csharp.util.TAG
import dev.ide.lang.signature.ParameterInfo
import dev.ide.lang.signature.SignatureHelp
import dev.ide.lang.signature.SignatureHelpRequest
import dev.ide.lang.signature.SignatureHelpService
import dev.ide.lang.signature.SignatureInfo
import kotlinx.coroutines.CancellationException

/**
 * Provides parameter information for C# calls.
 *
 * Roslyn finds the call at the caret and its overloads through
 * [CsSemanticCompletion.signaturesAt]. This class converts the result into the
 * editor's signature-help model.
 */
internal class CsSignatureHelp(
    private val semantic: CsSemanticCompletion,
) : SignatureHelpService {

    override suspend fun signatureHelp(request: SignatureHelpRequest): SignatureHelp? {
        val result = try {
            semantic.signaturesAt(
                request.document.file.path,
                request.document.text.toString(),
                request.offset,
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.d(TAG, "signature help: could not answer: $e")
            return null
        } ?: return null

        return SignatureHelp(
            signatures = result.signatures.map { signature ->
                SignatureInfo(
                    label = signature.label,
                    parameters = signature.parameters.mapIndexed { index, range ->
                        ParameterInfo(
                            label = signature.label.substring(range.first, range.last + 1),
                            labelStart = range.first,
                            labelEnd = range.last + 1,
                            documentation = signature.names.getOrNull(index)?.let { name ->
                                CsDocText.param(signature.documentation, name)
                            },
                        )
                    },
                    documentation = CsDocText.summary(signature.documentation),
                )
            },
            activeSignature = result.activeSignature,
            activeParameter = result.activeParameter,
        )
    }
}
