package com.tyron.completion.psi.codeInsight;

import androidx.annotation.NonNull;

import com.tyron.completion.TailType;
import com.tyron.completion.psi.codeInsight.daemon.impl.quickfix.ImportClassFixBase;
import com.tyron.completion.psi.search.PsiShortNamesCache;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.kotlin.com.intellij.openapi.project.Project;
import org.jetbrains.kotlin.com.intellij.openapi.util.NullableLazyValue;
import org.jetbrains.kotlin.com.intellij.openapi.util.VolatileNullableLazyValue;
import org.jetbrains.kotlin.com.intellij.psi.PsiClass;
import org.jetbrains.kotlin.com.intellij.psi.PsiClassType;
import org.jetbrains.kotlin.com.intellij.psi.PsiElement;
import org.jetbrains.kotlin.com.intellij.psi.PsiElementFactory;
import org.jetbrains.kotlin.com.intellij.psi.PsiMethod;
import org.jetbrains.kotlin.com.intellij.psi.PsiResolveHelper;
import org.jetbrains.kotlin.com.intellij.psi.PsiType;
import org.jetbrains.kotlin.com.intellij.psi.util.PsiUtil;
import org.jetbrains.kotlin.com.intellij.util.containers.ContainerUtil;

import java.util.List;
import java.util.function.Supplier;

public class ExpectedTypeInfoImpl implements ExpectedTypeInfo {
  public static final Supplier<String> NULL = () -> null;

  private final @NotNull PsiType myType;
  private final @NotNull PsiType myDefaultType;
  private final int myKind;
  private final @NotNull TailType myTailType;
  private final PsiMethod myCalledMethod;
  private final @NotNull Supplier<String> expectedNameComputable;
  private final @NotNull NullableLazyValue<String> expectedNameLazyValue;

  public ExpectedTypeInfoImpl(@NotNull PsiType type,
                              @Type int kind,
                              @NotNull PsiType defaultType,
                              @NotNull TailType tailType,
                              PsiMethod calledMethod,
                              @NotNull Supplier<String> expectedName) {
    myType = type;
    myKind = kind;

    myTailType = tailType;
    myDefaultType = defaultType;
    myCalledMethod = calledMethod;
    expectedNameComputable = expectedName;
    expectedNameLazyValue = new VolatileNullableLazyValue<String>() {
        @Override
        protected @Nullable String compute() {
            return expectedNameComputable.get();
        }
    };

    PsiUtil.ensureValidType(type);
    PsiUtil.ensureValidType(defaultType);
  }

  @Override
  public int getKind() {
    return myKind;
  }

  @NotNull
  @Override
  public TailType getTailType() {
    return myTailType;
  }

  @Nullable
  public String getExpectedName() {
    return expectedNameLazyValue.getValue();
  }

  @Override
  public PsiMethod getCalledMethod() {
    return myCalledMethod;
  }

  @Override
  @NotNull
  public PsiType getType () {
    return myType;
  }

  @Override
  @NotNull
  public PsiType getDefaultType () {
    return myDefaultType;
  }

  public boolean equals(final Object o) {
      if (this == o) {
          return true;
      }
      if (!(o instanceof ExpectedTypeInfoImpl)) {
          return false;
      }

      ExpectedTypeInfoImpl that = (ExpectedTypeInfoImpl) o;

      if (myKind != that.myKind) {
          return false;
      }
      if (!myDefaultType.equals(that.myDefaultType)) {
          return false;
      }
      if (!myTailType.equals(that.myTailType)) {
          return false;
      }
      if (!myType.equals(that.myType)) {
          return false;
      }

    return true;
  }

  public int hashCode() {
    int result = myType.hashCode();
    result = 31 * result + myDefaultType.hashCode();
    result = 31 * result + myKind;
    result = 31 * result + myTailType.hashCode();
    return result;
  }

  @Override
  public boolean equals(ExpectedTypeInfo obj) {
    return equals((Object)obj);
  }

  @NonNull
  public String toString() {
    return "ExpectedTypeInfo[type='" + myType + "' kind='" + myKind + "']";
  }

  @Override
  public ExpectedTypeInfo @NotNull [] intersect(@NotNull ExpectedTypeInfo info) {
    ExpectedTypeInfoImpl info1 = (ExpectedTypeInfoImpl)info;

    if (myKind == TYPE_STRICTLY) {
      if (info1.myKind == TYPE_STRICTLY) {
          if (info1.myType.equals(myType)) {
              return new ExpectedTypeInfoImpl[]{this};
          }
      }
      else {
        return info1.intersect(this);
      }
    }
    else if (myKind == TYPE_OR_SUBTYPE) {
      if (info1.myKind == TYPE_STRICTLY) {
          if (myType.isAssignableFrom(info1.myType)) {
              return new ExpectedTypeInfoImpl[]{info1};
          }
      }
      else if (info1.myKind == TYPE_OR_SUBTYPE) {
        PsiType otherType = info1.myType;
          if (myType.isAssignableFrom(otherType)) {
              return new ExpectedTypeInfoImpl[]{info1};
          } else if (otherType.isAssignableFrom(myType)) {
              return new ExpectedTypeInfoImpl[]{this};
          }
      }
      else {
        return info1.intersect(this);
      }
    }
    else if (myKind == TYPE_OR_SUPERTYPE) {
      if (info1.myKind == TYPE_STRICTLY) {
          if (info1.myType.isAssignableFrom(myType)) {
              return new ExpectedTypeInfoImpl[]{info1};
          }
      }
      else if (info1.myKind == TYPE_OR_SUBTYPE) {
          if (info1.myType.isAssignableFrom(myType)) {
              return new ExpectedTypeInfoImpl[]{this};
          }
      }
      else if (info1.myKind == TYPE_OR_SUPERTYPE) {
        PsiType otherType = info1.myType;
          if (myType.isAssignableFrom(otherType)) {
              return new ExpectedTypeInfoImpl[]{this};
          } else if (otherType.isAssignableFrom(myType)) {
              return new ExpectedTypeInfoImpl[]{info1};
          }
      }
      else {
        return info1.intersect(this);
      }
    }


    //todo: the following cases are not implemented: SUPERxSUB, SUBxSUPER

    return ExpectedTypeInfo.EMPTY_ARRAY;
  }

  @NotNull
  ExpectedTypeInfoImpl fixUnresolvedTypes(@NotNull PsiElement context) {
    PsiType resolvedType = fixUnresolvedType(context, myType);
    PsiType resolvedDefaultType = fixUnresolvedType(context, myDefaultType);
    if (resolvedType != myType || resolvedDefaultType != myDefaultType) {
      return new ExpectedTypeInfoImpl(resolvedType, myKind, resolvedDefaultType, myTailType, myCalledMethod, expectedNameComputable);
    }
    return this;
  }

  @NotNull
  private static PsiType fixUnresolvedType(@NotNull PsiElement context, @NotNull PsiType type) {
    if (type instanceof PsiClassType && ((PsiClassType)type).resolve() == null) {
      String className = ((PsiClassType)type).getClassName();
      int typeParamCount = ((PsiClassType)type).getParameterCount();
      Project project = context.getProject();
      PsiResolveHelper helper = project.getService(PsiResolveHelper.class);
      List<PsiClass> suitableClasses = ContainerUtil.filter(
        PsiShortNamesCache.getInstance(project).getClassesByName(className, context.getResolveScope()),
        c -> (typeParamCount == 0 || c.hasTypeParameters()) &&
             helper.isAccessible(c, context, null) &&
             ImportClassFixBase.qualifiedNameAllowsAutoImport(context.getContainingFile(), c));
      if (suitableClasses.size() == 1) {
        return PsiElementFactory.getInstance(project).createType(suitableClasses.get(0), ((PsiClassType)type).getParameters());
      }
        throw new UnsupportedOperationException();
    }
    return type;
  }

}