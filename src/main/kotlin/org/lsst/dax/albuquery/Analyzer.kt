/* This file is part of albuquery.
 *
 * Developed for the LSST Data Management System.
 * This product includes software developed by the LSST Project
 * (https://www.lsst.org).
 * See the COPYRIGHT file at the top-level directory of this distribution
 * for details of code ownership.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program. If not, see <https://www.gnu.org/licenses/>.
 */

package org.lsst.dax.albuquery

import com.facebook.presto.sql.parser.ParsingException
import com.facebook.presto.sql.tree.AliasedRelation
import com.facebook.presto.sql.tree.AllColumns
import com.facebook.presto.sql.tree.DefaultTraversalVisitor
import com.facebook.presto.sql.tree.DereferenceExpression
import com.facebook.presto.sql.tree.Identifier
import com.facebook.presto.sql.tree.Join
import com.facebook.presto.sql.tree.QualifiedName
import com.facebook.presto.sql.tree.QuerySpecification
import com.facebook.presto.sql.tree.Relation
import com.facebook.presto.sql.tree.SingleColumn
import com.facebook.presto.sql.tree.SubqueryExpression
import com.facebook.presto.sql.tree.Table
import org.lsst.dax.albuquery.dao.MetaservDAO
import java.net.URI
import javax.ws.rs.core.UriBuilder

class Analyzer {

    class TableAndColumnExtractor : DefaultTraversalVisitor<Void, Void>() {
        val columns = arrayListOf<ParsedColumn>()
        val allColumnTables = arrayListOf<QualifiedName>()
        val tables = arrayListOf<ParsedTable>()
        var allColumns = false

        override fun visitSubqueryExpression(node: SubqueryExpression?, context: Void?): Void? {
            return null
        }

        override fun visitQuerySpecification(node: QuerySpecification, context: Void?): Void? {
            val relations = arrayListOf<Relation>()
            for ((index, item) in node.select.selectItems.withIndex()) {
                val position = index + 1

                if (item is SingleColumn) {
                    val column = item
                    val expression = column.expression
                    var qualifiedName: QualifiedName? = null
                    when (expression) {
                        is Identifier -> {
                            qualifiedName = QualifiedName.of(expression.value)
                        }
                        is DereferenceExpression -> {
                            // Workaround because DereferenceExpression.getQualifiedName destroys original case
                            var base = expression.base
                            val parts = arrayListOf<String>()
                            parts.add(expression.field.value)
                            while (base is DereferenceExpression) {
                                parts.add(base.field.value)
                                base = base.base
                            }
                            if (base is Identifier) {
                                parts.add(base.value)
                            }
                            qualifiedName = QualifiedName.of(parts.reversed())
                        }
                    }
                    if (qualifiedName != null) {
                        // val name = qualifiedName.toString()
                        val alias = column.alias.orElse(null)?.value
                        columns.add(ParsedColumn(nameOf(qualifiedName), qualifiedName, alias, position))
                    }
                }

                if (item is AllColumns) {
                    if (item.prefix.isPresent) {
                        allColumnTables.add(item.prefix.get())
                        val parts = arrayListOf<String>()
                        parts.addAll(item.prefix.get().originalParts)
                        parts.add("*")
                        val qualifiedName = QualifiedName.of(parts)
                        columns.add(ParsedColumn(nameOf(qualifiedName), qualifiedName, null, position))
                    } else {
                        columns.add(ParsedColumn("*", QualifiedName.of("*"), null, position))
                        allColumns = true
                    }
                }
            }

            if (node.from.isPresent) {
                val from = node.from.get()
                var relation: Join
                relations.clear()
                if (from is Join) {
                    relation = from
                    relations.add(relation.right)
                    while (relation.left is Join) {
                        relation = relation.left as Join
                        relations.add(relation.right)
                    }
                    relations.add(relation.left)
                } else {
                    relations.add(from)
                }
            }
            relations.reverse()

            for (index in relations.indices) {
                val position = index + 1
                var relation = relations[index]
                var alias: String? = null
                if (relation is AliasedRelation) {
                    alias = relation.alias.value
                    relation = relation.relation
                }
                if (relation is Table) {
                    tables.add(ParsedTable(nameOf(relation.name), relation.name, alias, position))
                }
            }
            return null
        }
    }

    companion object {
        val DBURI = Regex("//.*")

        fun getDatabaseURI(metaservDAO: MetaservDAO, extractedTables: List<ParsedTable>): URI {
            // Use the first table found to
            val firstInstanceTable = findInstanceIdentifyingTable(extractedTables)
            val instanceIdentifier = firstInstanceTable.parts.get(0)

            // FIXME: MySQL specific
            val mysqlScheme = "mysql"
            var dbUri: URI? = null
            if (instanceIdentifier.matches(DBURI)) {
                val givenUri = URI(instanceIdentifier)
                dbUri = URI(mysqlScheme, null, givenUri.host, givenUri.port,
                    givenUri.path, null, null)
            }

            // FIXME: If this is too slow, use a Guava LoadingCache
            val db = metaservDAO.findDatabaseByName(instanceIdentifier)
            if (db != null) {
                val defaultSchema = metaservDAO.findDefaultSchemaByDatabaseId(db.id)
                dbUri = UriBuilder.fromPath(defaultSchema?.name ?: "")
                    .host(db.host)
                    .port(db.port)
                    .scheme(mysqlScheme)
                    .build()
            }
            if (dbUri == null) {
                throw ParsingException("No database instance identified: $firstInstanceTable")
            }
            return dbUri
        }

        fun findInstanceIdentifyingTable(relations: List<ParsedTable>): QualifiedName {
            var firstTable: QualifiedName? = null
            for (table in relations) {
                if (table.qualifiedName.parts.size == 3) {
                    firstTable = table.qualifiedName
                    break
                }
            }
            if (firstTable == null) {
                // Not sure if this can happen
                throw ParsingException("Unable to determine a table")
            }
            return firstTable
        }

        private fun nameOf(name: QualifiedName): String {
            val suffix = name.originalParts.last()
            return suffix
        }
    }
}
