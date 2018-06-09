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

import com.facebook.presto.sql.tree.QualifiedName
import org.lsst.dax.albuquery.model.metaserv.Column
import org.lsst.dax.albuquery.model.metaserv.Table
import java.util.LinkedHashMap

class QueryMetadataHelper(val analyzer: Analyzer.TableAndColumnExtractor) {

    fun associateMetadata(
        jdbcColumnMetadata: LinkedHashMap<String, JdbcColumnMetadata>,
        possibleTablesAndColumns: Map<ParsedTable, Pair<Table, List<Column>>>
    ): ArrayList<ColumnMetadata> {

        val parsedTableMapping = buildTableNameAndAliasMapping()

        val parsedColumnToParsedTable = hashMapOf<ParsedColumn, ParsedTable>()
        val parsedColumnToColumn = hashMapOf<ParsedColumn, Column>()
        val parsedTableToColumns = hashMapOf<ParsedTable, Map<String, Column>>()
        val defaultColumnMap = hashMapOf<String, Column>()
        for ((parsedTable, metaservInfo) in possibleTablesAndColumns) {
            val (_, metaservColumns) = metaservInfo
            parsedTableToColumns[parsedTable] = metaservColumns.associateBy({ it.name }, { it })
        }

        for (column in analyzer.columns) {
            if (column.qualifiedName.prefix.isPresent) {
                val parsedTable = parsedTableMapping[column.qualifiedName.prefix.get()]
                if (parsedTable != null) {
                    parsedColumnToParsedTable[column] = parsedTable
                    val metaservColumns = parsedTableToColumns[parsedTable]
                    if (metaservColumns != null && column.identifier in metaservColumns) {
                        parsedColumnToColumn[column] = metaservColumns[column.identifier]!!
                    }
                }
            }
        }

        for (metaservInfo in possibleTablesAndColumns.values) {
            val (_, metaservColumns) = metaservInfo
            for (metaservColumn in metaservColumns) {
                if (metaservColumn.name !in defaultColumnMap) {
                    // This is for fallbacks. We want to make sure everything is lowercase in that case
                    defaultColumnMap[metaservColumn.name.toLowerCase()] = metaservColumn
                }
            }
        }
        val columnPositionMapping = analyzer.columns.associateBy({ it.position }, { it })
        val columnMetadataList: ArrayList<ColumnMetadata> = arrayListOf()
        for ((name, jdbcColumn) in jdbcColumnMetadata) {
            val parsedColumn = columnPositionMapping[jdbcColumn.ordinal]
            var metaservColumn: Column? = parsedColumnToColumn[parsedColumn]
                ?: defaultColumnMap[parsedColumn?.identifier?.toLowerCase()]

            if (metaservColumn == null && analyzer.allColumns) {
                metaservColumn = defaultColumnMap[jdbcColumn.name.toLowerCase()]
            }

            if (metaservColumn == null && !analyzer.allColumnTables.isEmpty()) {
                // FIXME: Special logic for SELECT foo.*, bar.* FROM baz ? Not sure...
                // val table = parsedColumn.qualifiedName.prefix.get()
                metaservColumn = defaultColumnMap[jdbcColumn.name.toLowerCase()]
            }

            val columnMetadata =
                ColumnMetadata(name,
                    datatype = metaservColumn?.datatype ?: jdbcToLsstType(jdbcColumn.jdbcType),
                    description = metaservColumn?.description ?: "",
                    ucd = metaservColumn?.ucd,
                    unit = metaservColumn?.unit,
                    tableName = metaservColumn?.tableName ?: jdbcColumn.tableName,
                    jdbcType = jdbcColumn.jdbcType)
            columnMetadataList.add(columnMetadata)
        }
        return columnMetadataList
    }

    fun buildTableNameAndAliasMapping(): Map<QualifiedName, ParsedTable> {
        val tableNameMapping = hashMapOf<QualifiedName, ParsedTable>()
        for (table in analyzer.tables) {
            if (table.alias != null) {
                tableNameMapping[QualifiedName.of(table.alias)] = table
            }
            tableNameMapping[table.qualifiedName] = table
        }
        return tableNameMapping
    }
}
