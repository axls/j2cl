package com.google.j2cl.ast.visitors;

import com.google.common.base.Preconditions;
import com.google.j2cl.ast.AbstractRewriter;
import com.google.j2cl.ast.ArrayTypeDescriptor;
import com.google.j2cl.ast.CastExpression;
import com.google.j2cl.ast.CompilationUnit;
import com.google.j2cl.ast.Expression;
import com.google.j2cl.ast.MethodCall;
import com.google.j2cl.ast.MethodDescriptor;
import com.google.j2cl.ast.Node;
import com.google.j2cl.ast.NumberLiteral;
import com.google.j2cl.ast.RegularTypeDescriptor;
import com.google.j2cl.ast.TypeDescriptor;
import com.google.j2cl.ast.TypeDescriptors;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Replaces cast expression with corresponding cast method call.
 */
public class NormalizeCastsVisitor extends AbstractRewriter {
  public static void doNormalizeCasts(CompilationUnit compilationUnit) {
    new NormalizeCastsVisitor().normalizeCasts(compilationUnit);
  }

  private void normalizeCasts(CompilationUnit compilationUnit) {
    compilationUnit.accept(this);
  }

  @Override
  public Node rewriteCastExpression(CastExpression expression) {
    TypeDescriptor castTypeDescriptor = expression.getCastTypeDescriptor();
    if (castTypeDescriptor.isArray()) {
      return rewriteArrayCastExpression(expression);
    } else if (castTypeDescriptor.isPrimitive()) {
      return rewritePrimitiveCastExpression(expression);
    }
    return rewriteRegularCastExpression(expression);
  }

  private Node rewriteRegularCastExpression(CastExpression castExpression) {
    Preconditions.checkArgument(
        castExpression.getCastTypeDescriptor() instanceof RegularTypeDescriptor);
    RegularTypeDescriptor castTypeDescriptor =
        (RegularTypeDescriptor) castExpression.getCastTypeDescriptor();
    Expression expression = castExpression.getExpression();

    // TypeName.$isInstance(expr);
    MethodDescriptor isInstanceMethodDescriptor =
        MethodDescriptor.createRaw(
            true, castTypeDescriptor, "$isInstance", TypeDescriptors.BOOLEAN_TYPE_DESCRIPTOR);
    Expression isInstanceMethodCall =
        new MethodCall(null, isInstanceMethodDescriptor, Arrays.asList(expression));

    MethodDescriptor castToMethodDescriptor =
        MethodDescriptor.createRaw(
            true, TypeDescriptors.VM_CASTS_TYPE_DESCRIPTOR, "to", castTypeDescriptor);
    List<Expression> arguments = new ArrayList<>();
    arguments.add(expression);
    arguments.add(isInstanceMethodCall);

    // Casts.to(expr, TypeName.$isInstance(expr));
    MethodCall castMethodCall = new MethodCall(null, castToMethodDescriptor, arguments);
    // /**@type {}*/ ()
    return new CastExpression(castMethodCall, castTypeDescriptor);
  }

  private Node rewriteArrayCastExpression(CastExpression castExpression) {
    Preconditions.checkArgument(
        castExpression.getCastTypeDescriptor() instanceof ArrayTypeDescriptor);
    ArrayTypeDescriptor arrayCastTypeDescriptor =
        (ArrayTypeDescriptor) castExpression.getCastTypeDescriptor();

    MethodDescriptor castToMethodDescriptor =
        MethodDescriptor.createRaw(
            true, TypeDescriptors.VM_ARRAYS_TYPE_DESCRIPTOR, "$castTo", arrayCastTypeDescriptor);
    List<Expression> arguments = new ArrayList<>();
    arguments.add(castExpression.getExpression());
    arguments.add(arrayCastTypeDescriptor.getLeafTypeDescriptor());
    arguments.add(
        new NumberLiteral(
            TypeDescriptors.INT_TYPE_DESCRIPTOR, arrayCastTypeDescriptor.getDimensions()));

    // Arrays.$castTo(expr, leafType, dimension);
    MethodCall castMethodCall = new MethodCall(null, castToMethodDescriptor, arguments);
    // /**@type {}*/ ()
    return new CastExpression(castMethodCall, arrayCastTypeDescriptor);
  }

  private Node rewritePrimitiveCastExpression(CastExpression castExpression) {
    Preconditions.checkArgument(castExpression.getCastTypeDescriptor().isPrimitive());

    Expression expression = castExpression.getExpression();
    TypeDescriptor fromTypeDescriptor = expression.getTypeDescriptor();
    TypeDescriptor toTypeDescriptor = castExpression.getCastTypeDescriptor();
    if (canRemoveCast(fromTypeDescriptor, toTypeDescriptor)) {
      return expression;
    }
    String castMethodName =
        String.format(
            "$cast%sTo%s",
            toProperCase(fromTypeDescriptor.getSimpleName()),
            toProperCase(toTypeDescriptor.getSimpleName()));
    MethodDescriptor castToMethodDescriptor =
        MethodDescriptor.createRaw(
            true, TypeDescriptors.VM_PRIMITIVES_TYPE_DESCRIPTOR, castMethodName, toTypeDescriptor);
    // Primitives.$castAToB(expr);
    return new MethodCall(null, castToMethodDescriptor, Arrays.asList(expression));
  }

  /**
   * The following is the cast table between primitive types. The cell marked as 'X'
   * indicates that no cast is needed.
   * <p>
   * For other cases, cast from A to B is translated to method call $castAToB.
   * <p>
   * from\to       byte |  char | short | int   | long | float | double|
   * -------------------------------------------------------------------
   * byte        |  X   |       |   X   |   X   |      |   X   |   X   |
   * -------------------------------------------------------------------
   * char        |      |   X   |       |   X   |      |   X   |   X   |
   * -------------------------------------------------------------------
   * short       |      |       |   X   |   X   |      |   X   |   X   |
   * -------------------------------------------------------------------
   * int         |      |       |       |   X   |      |   X   |   X   |
   * -------------------------------------------------------------------
   * long        |      |       |       |       |   X  |       |       |
   * -------------------------------------------------------------------
   * float       |      |       |       |       |      |   X   |   X   |
   * -------------------------------------------------------------------
   * double      |      |       |       |       |      |   X   |   X   |
   */
  private boolean canRemoveCast(
      TypeDescriptor fromTypeDescriptor, TypeDescriptor toTypeDescriptor) {
    if (fromTypeDescriptor == toTypeDescriptor) {
      return true;
    }
    if (fromTypeDescriptor == TypeDescriptors.LONG_TYPE_DESCRIPTOR
        || toTypeDescriptor == TypeDescriptors.LONG_TYPE_DESCRIPTOR) {
      return false;
    }
    return toTypeDescriptor == TypeDescriptors.FLOAT_TYPE_DESCRIPTOR
        || toTypeDescriptor == TypeDescriptors.DOUBLE_TYPE_DESCRIPTOR
        || (toTypeDescriptor == TypeDescriptors.INT_TYPE_DESCRIPTOR
            && (fromTypeDescriptor == TypeDescriptors.BYTE_TYPE_DESCRIPTOR
                || fromTypeDescriptor == TypeDescriptors.CHAR_TYPE_DESCRIPTOR
                || fromTypeDescriptor == TypeDescriptors.SHORT_TYPE_DESCRIPTOR))
        || (toTypeDescriptor == TypeDescriptors.SHORT_TYPE_DESCRIPTOR
            && fromTypeDescriptor == TypeDescriptors.BYTE_TYPE_DESCRIPTOR);
  }

  private String toProperCase(String string) {
    if (string.isEmpty()) {
      return string;
    } else if (string.length() == 1) {
      return string.toUpperCase();
    }
    return string.substring(0, 1).toUpperCase() + string.substring(1, string.length());
  }
}
