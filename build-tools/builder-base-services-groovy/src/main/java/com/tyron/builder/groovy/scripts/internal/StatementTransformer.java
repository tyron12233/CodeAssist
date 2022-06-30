package com.tyron.builder.groovy.scripts.internal;

import org.codehaus.groovy.ast.stmt.Statement;
import org.codehaus.groovy.control.SourceUnit;

/**
 * A transformer of individual statements.
 */
public interface StatementTransformer {

    // Return null to remove the statement
    Statement transform(SourceUnit sourceUnit, Statement statement);

}
