// Copyright 2000-2020 JetBrains s.r.o. Use of this source code is governed by the Apache 2.0 license that can be found in the LICENSE file.
package org.jetbrains.vuejs.libraries.vuex.model.store

import com.intellij.lang.javascript.psi.JSType
import com.intellij.lang.javascript.psi.ecma6.impl.JSLocalImplicitElementImpl
import com.intellij.lang.javascript.psi.stubs.JSImplicitElement
import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiElement
import com.intellij.psi.util.CachedValueProvider
import com.intellij.psi.util.CachedValuesManager
import com.intellij.psi.util.PsiModificationTracker
import java.util.*

class VuexStoreStateElement(name: String, private val qualifiedStoreName: String, location: PsiElement, jsType: JSType?)
  : JSLocalImplicitElementImpl(name, jsType, location, JSImplicitElement.Type.Property) {

  override fun getTextRange(): TextRange? {
    return myProvider!!.textRange
  }

  override fun equals(other: Any?): Boolean {
    return (other is VuexStoreStateElement)
           && other.qualifiedStoreName == qualifiedStoreName
           && other.myProvider == myProvider
           && other.myKind == myKind
           && ((other.jsType == null && jsType == null)
               || (other.jsType?.isEquivalentTo(this.jsType, null) == true))

  }

  override fun isEquivalentTo(another: PsiElement?): Boolean {
    if (another == myProvider) {
      return true
    }
    val candidates = getResolveCandidates()
    return another != null && candidates.asSequence()
      .mapNotNull { if (it is JSImplicitElement) it.context else it }
      .any { it.isEquivalentTo(another) || another.isEquivalentTo(it) }
  }

  override fun hashCode(): Int {
    return Objects.hash(javaClass, myName, myProvider, myKind)
  }

  private fun getResolveCandidates(): Collection<PsiElement> = CachedValuesManager.getCachedValue(this) {
    val result = mutableSetOf<PsiElement>()

    VuexModelManager.getVuexStoreContext(myProvider!!)?.let { context ->
      context.visitSymbols(VuexContainer::state) { qualifiedName: String, symbol: VuexNamedSymbol ->
        if (qualifiedName == this.qualifiedStoreName) {
          result.add(symbol.source)
        }
      }
      context.visit { qualifiedName: String, symbol: VuexContainer ->
        if (qualifiedName == this.qualifiedStoreName) {
          result.add(symbol.source)
        } else {
          val prefix = VuexStoreContext.appendSegment(qualifiedName, "")
          if (qualifiedStoreName.startsWith(prefix)
              && qualifiedStoreName.length > prefix.length) {
            val segments = qualifiedStoreName.substring(prefix.length).split("/")
            var index = 0
            var signature = symbol.state[segments[0]]?.getPropertySignature(prefix, VuexStoreContext.appendSegment(prefix, segments[0]))
            while (signature != null && ++index < segments.size) {
              // TODO improve resolution
              signature = signature.jsType?.asRecordType()?.findPropertySignature(segments[index])
            }
            if (signature != null) {
              result.addAll(signature.memberSource.allSourceElements)
            }
          }
        }
      }
    }
    CachedValueProvider.Result.create(result, PsiModificationTracker.MODIFICATION_COUNT)
  }
}