package org.orbisgis.osm.utils

import org.orbisgis.orbisdata.datamanager.jdbc.JdbcDataSource
import org.orbisgis.osm.OSMTools
import org.slf4j.Logger
import org.slf4j.LoggerFactory

/**
 * Class containing utility methods for the {@link org.orbisgis.osm.Transform} script to keep only processes inside
 * the groovy script.
 */
class TransformUtils {

    private static Logger LOGGER = LoggerFactory.getLogger(TransformUtils)

    /** {@link Closure} returning a {@link String} prefix/suffix build from a random {@link UUID} with '-' replaced by '_'. */
    static def getUuid() { UUID.randomUUID().toString().replaceAll("-", "_") }
    static def uuid = {getUuid()}
    /** {@link Closure} logging with INFO level the given {@link Object} {@link String} representation. */
    static def info = { obj -> LOGGER.info(obj.toString()) }
    /** {@link Closure} logging with WARN level the given {@link Object} {@link String} representation. */
    static def warn = { obj -> LOGGER.warn(obj.toString()) }
    /** {@link Closure} logging with ERROR level the given {@link Object} {@link String} representation. */
    static def error = { obj -> LOGGER.error(obj.toString()) }

    /**
     * Merge arrays into one.
     *
     * @param removeDuplicated If true remove duplicated values.
     * @param arrays Array of collections or arrays to merge.
     *
     * @return One array containing the values of the given arrays.
     */
    static def arrayUnion(boolean removeDuplicated, Collection... arrays) {
        def union = []
        if(arrays == null) return union
        for (Object[] array : arrays) {
            union.addAll(array)
        }
        if (removeDuplicated) union.unique()
        union.sort()
        return union
    }

    /**
     * Extract all the polygons/lines from the OSM tables
     *
     * @param type
     * @param datasource a connection to a database
     * @param osmTablesPrefix prefix name for OSM tables
     * @param epsgCode EPSG code to reproject the geometries
     * @param tags list of keys and values to be filtered
     * @param columnsToKeep a list of columns to keep. The name of a column corresponds to a key name
     *
     * @return The name for the table that contains all polygons/lines
     */
    static def toPolygonOrLine(String type, datasource, osmTablesPrefix, epsgCode, tags, columnsToKeep) {
        //Check if parameters a good
        if (!datasource) {
            error "Please set a valid database connection"
            return
        }
        if (epsgCode == -1) {
            error "Invalid EPSG code : $epsgCode"
            return
        }

        //Get the processes according to the type
        def waysProcess
        def relationsProcess
        switch (type) {
            case "POLYGONS":
                waysProcess = OSMTools.Transform.extractWaysAsPolygons()
                relationsProcess = OSMTools.Transform.extractRelationsAsPolygons()
                break
            case "LINES":
                waysProcess = OSMTools.Transform.extractWaysAsLines()
                relationsProcess = OSMTools.Transform.extractRelationsAsLines()
                break
            default:
                error "Wrong type '$type'."
                return
        }

        //Start the transformation
        def outputTableName = "OSM_${type}_" + uuid()
        info "Start ${type.toLowerCase()} transformation"
        info "Indexing osm tables..."
        buildIndexes(datasource, osmTablesPrefix)

        waysProcess(datasource: datasource,
                osmTablesPrefix: osmTablesPrefix,
                epsgCode: epsgCode,
                tags: tags,
                columnsToKeep: columnsToKeep)
        def outputWay = waysProcess.getResults().outputTableName

        relationsProcess(datasource: datasource,
                osmTablesPrefix: osmTablesPrefix,
                epsgCode: epsgCode,
                tags: tags,
                columnsToKeep: columnsToKeep)
        def outputRelation = relationsProcess.getResults().outputTableName

        if (outputWay && outputRelation) {
            //Merge ways and relations
            def columnsWays = datasource.getTable(outputWay).columns
            def columnsRelations = datasource.getTable(outputRelation).columns
            def allColumns = arrayUnion(true, columnsWays, columnsRelations)
            def leftSelect = ""
            def rightSelect = ""
            allColumns.each { column ->
                leftSelect += columnsWays.contains(column) ? "\"$column\"," : "null AS \"$column\","
                rightSelect += columnsRelations.contains(column) ? "\"$column\"," : "null AS \"$column\","
            }
            leftSelect = leftSelect[0..-2]
            rightSelect = rightSelect[0..-2]

            datasource.execute """
                            DROP TABLE IF EXISTS $outputTableName;
                            CREATE TABLE $outputTableName AS 
                                SELECT $leftSelect
                                FROM $outputWay
                                UNION ALL
                                SELECT $rightSelect
                                FROM $outputRelation;
                            DROP TABLE IF EXISTS $outputWay, $outputRelation;
            """
            info "The way and relation $type have been built."
        } else if (outputWay) {
            datasource.execute "ALTER TABLE $outputWay RENAME TO $outputTableName"
            info "The way $type have been built."
        } else if (outputRelation) {
            datasource.execute "ALTER TABLE $outputRelation RENAME TO $outputTableName"
            info "The relation $type have been built."
        } else {
            warn "Cannot extract any $type."
            return
        }
        [outputTableName: outputTableName]
    }

    /**
     * Return the column selection query
     *
     * @param osmTableTag Name of the table of OSM tag
     * @param tags List of keys and values to be filtered
     * @param columnsToKeep List of columns to keep
     *
     * @return The column selection query
     */
    static def getColumnSelector(osmTableTag, tags, columnsToKeep) {
        if (!osmTableTag) {
            error "The table name should not be empty or null."
            return null
        }
        def tagKeys = []
        if (tags != null) {
            def tagKeysList = tags in Map ? tags.keySet() : tags
            tagKeys.addAll(tagKeysList.findResults { it != null && it != "null" && !it.isEmpty() ? it : null })
        }
        if (columnsToKeep != null) {
            tagKeys.addAll(columnsToKeep)
        }
        tagKeys.removeAll([null])

        def query = "SELECT distinct tag_key FROM $osmTableTag"
        if (tagKeys) query += " WHERE tag_key IN ('${tagKeys.unique().join("','")}')"
        return query
    }

    /**
     * Return the tag count query
     *
     * @param osmTableTag Name of the table of OSM tag
     * @param tags List of keys and values to be filtered
     * @param columnsToKeep List of columns to keep
     *
     * @return The tag count query
     */
    static def getCountTagsQuery(osmTableTag, tags) {
        if(!osmTableTag) return null
        def countTagsQuery = "SELECT count(*) AS count FROM $osmTableTag"
        def whereFilter = createWhereFilter(tags)
        if (whereFilter) {
            countTagsQuery += " WHERE $whereFilter"
        }
        return countTagsQuery
    }

    /**
     * This function is used to extract nodes as points
     *
     * @param datasource a connection to a database
     * @param osmTablesPrefix prefix name for OSM tables
     * @param epsgCode EPSG code to reproject the geometries
     * @param outputNodesPoints the name of the nodes points table
     * @param tagKeys list ok keys to be filtered
     * @param columnsToKeep a list of columns to keep.
     * The name of a column corresponds to a key name
     *
     * @return true if some points have been built
     *
     * @author Erwan Bocher (CNRS LAB-STICC)
     * @author Elisabeth Lesaux (UBS LAB-STICC)
     */
    static boolean extractNodesAsPoints(JdbcDataSource datasource, String osmTablesPrefix, int epsgCode,
                                 String outputNodesPoints, def tags, def columnsToKeep) {
        if(!datasource){
            error("The datasource should not be null")
            return false
        }
        if(osmTablesPrefix == null){
            error("Invalid null OSM table prefix")
            return false
        }
        if(epsgCode < 0){
            error("Invalid EPSG code")
            return false
        }
        if(outputNodesPoints == null){
            error("Invalid null output node points table name")
            return false
        }
        def tableNode = "${osmTablesPrefix}_node"
        def tableNodeTag = "${osmTablesPrefix}_node_tag"
        def countTagsQuery = getCountTagsQuery(tableNodeTag, tags)
        def columnsSelector = getColumnSelector(tableNodeTag, tags, columnsToKeep)
        def tagsFilter = createWhereFilter(tags)

        if (datasource.firstRow(countTagsQuery).count <= 0) {
            info("No keys or values found in the nodes")
            return false
        }
        info "Build nodes as points"
        def tagList = createTagList datasource, columnsSelector
        if (tagsFilter.isEmpty()) {
            datasource.execute """
                DROP TABLE IF EXISTS $outputNodesPoints;
                CREATE TABLE $outputNodesPoints AS
                    SELECT a.id_node,ST_TRANSFORM(ST_SETSRID(a.THE_GEOM, 4326), $epsgCode) AS the_geom $tagList
                    FROM $tableNode AS a, $tableNodeTag b
                    WHERE a.id_node = b.id_node GROUP BY a.id_node;
            """
        } else {
            def filteredNodes = "FILTERED_NODES_" + uuid()
            datasource.execute """
                CREATE TABLE $filteredNodes AS
                    SELECT DISTINCT id_node FROM ${osmTablesPrefix}_node_tag WHERE $tagsFilter;
                CREATE INDEX ON $filteredNodes(id_node);
                DROP TABLE IF EXISTS $outputNodesPoints;
                CREATE TABLE $outputNodesPoints AS
                    SELECT a.id_node, ST_TRANSFORM(ST_SETSRID(a.THE_GEOM, 4326), $epsgCode) AS the_geom $tagList
                    FROM $tableNode AS a, $tableNodeTag  b, $filteredNodes c
                    WHERE a.id_node=b.id_node
                    AND a.id_node=c.id_node
                    GROUP BY a.id_node;
            """
        }
        return true
    }

    /**
     * Method to build a where filter based on a list of key, values
     *
     * @param tags the input Map of key and values with the following signature
     * ["building", "landcover"] or
     * ["building": ["yes"], "landcover":["grass", "forest"]]
     * @return a where filter as
     * tag_key in '(building', 'landcover') or
     * (tag_key = 'building' and tag_value in ('yes')) or (tag_key = 'landcover' and tag_value in ('grass','forest')))
     */
    static def createWhereFilter(def tags){
        if(!tags){
            warn "The tag map is empty"
            return ""
        }
        def whereKeysValuesFilter = ""
        if(tags in Map){
            def whereQuery = []
            tags.each{ tag ->
                def keyIn = ''
                def valueIn = ''
                if(tag.key){
                    if(tag.key instanceof Collection) {
                        keyIn += "tag_key IN ('${tag.key.join("','")}')"
                    }
                    else {
                        keyIn += "tag_key = '${tag.key}'"
                    }
                }
                if(tag.value){
                    def valueList = (tag.value instanceof Collection) ? tag.value.flatten().findResults{it} : [tag.value]
                    valueIn += "tag_value IN ('${valueList.join("','")}')"
                }

                if(!keyIn.isEmpty()&& !valueIn.isEmpty()){
                    whereQuery+= "$keyIn AND $valueIn"
                }
                else if(!keyIn.isEmpty()){
                    whereQuery+= "$keyIn"
                }
                else if(!valueIn.isEmpty()){
                    whereQuery+= "$valueIn"
                }
            }
            whereKeysValuesFilter = "(${whereQuery.join(') OR (')})"
        }
        else {
            def tagArray = []
            tagArray.addAll(tags)
            tagArray.removeAll([null])
            if(tagArray) {
                whereKeysValuesFilter = "tag_key IN ('${tagArray.join("','")}')"
            }
        }
        return whereKeysValuesFilter
    }

    /**
     * Build a case when expression to pivot keys
     * @param datasource a connection to a database
     * @param selectTableQuery the table that contains the keys and values to pivot
     * @return the case when expression
     */
    static def createTagList(datasource, selectTableQuery){
        if(!datasource){
            error "The datasource should not be null."
            return null
        }
        def rowskeys = datasource.rows(selectTableQuery)
        def list = []
        rowskeys.tag_key.each { it ->
            if(it != null)
                list << "MAX(CASE WHEN b.tag_key = '$it' THEN b.tag_value END) AS \"${it.toUpperCase()}\""
        }
        def tagList =""
        if (!list.isEmpty()) {
            tagList = ", ${list.join(",")}"
        }
        return tagList
    }

    /**
     * Build the indexes to perform analysis quicker
     *
     * @param datasource a connection to a database
     * @param osmTablesPrefix prefix name for OSM tables
     *
     * @return
     *
     * @author Erwan Bocher (CNRS LAB-STICC)
     * @author Elisabeth Lesaux (UBS LAB-STICC)
     */
    static def buildIndexes(JdbcDataSource datasource, String osmTablesPrefix){
        if(!datasource){
            error "The datasource should not be null."
            return false
        }
        if(!osmTablesPrefix){
            error "The osmTablesPrefix should not be null or empty."
            return false
        }
        datasource.execute """
            CREATE INDEX IF NOT EXISTS ${osmTablesPrefix}_node_index                     ON ${osmTablesPrefix}_node(id_node);
            CREATE INDEX IF NOT EXISTS ${osmTablesPrefix}_way_node_id_node_index         ON ${osmTablesPrefix}_way_node(id_node);
            CREATE INDEX IF NOT EXISTS ${osmTablesPrefix}_way_node_order_index           ON ${osmTablesPrefix}_way_node(node_order);
            CREATE INDEX IF NOT EXISTS ${osmTablesPrefix}_way_node_id_way_index          ON ${osmTablesPrefix}_way_node(id_way);
            CREATE INDEX IF NOT EXISTS ${osmTablesPrefix}_way_index                      ON ${osmTablesPrefix}_way(id_way);
            CREATE INDEX IF NOT EXISTS ${osmTablesPrefix}_way_tag_key_tag_index          ON ${osmTablesPrefix}_way_tag(tag_key);
            CREATE INDEX IF NOT EXISTS ${osmTablesPrefix}_way_tag_id_way_index           ON ${osmTablesPrefix}_way_tag(id_way);
            CREATE INDEX IF NOT EXISTS ${osmTablesPrefix}_way_tag_value_index            ON ${osmTablesPrefix}_way_tag(tag_value);
            CREATE INDEX IF NOT EXISTS ${osmTablesPrefix}_relation_id_relation_index     ON ${osmTablesPrefix}_relation(id_relation);
            CREATE INDEX IF NOT EXISTS ${osmTablesPrefix}_relation_tag_key_tag_index     ON ${osmTablesPrefix}_relation_tag(tag_key);
            CREATE INDEX IF NOT EXISTS ${osmTablesPrefix}_relation_tag_id_relation_index ON ${osmTablesPrefix}_relation_tag(id_relation);
            CREATE INDEX IF NOT EXISTS ${osmTablesPrefix}_relation_tag_tag_value_index   ON ${osmTablesPrefix}_relation_tag(tag_value);
            CREATE INDEX IF NOT EXISTS ${osmTablesPrefix}_way_member_id_relation_index   ON ${osmTablesPrefix}_way_member(id_relation);
            CREATE INDEX IF NOT EXISTS ${osmTablesPrefix}_way_id_way                     ON ${osmTablesPrefix}_way(id_way);
        """
        return true
    }
}