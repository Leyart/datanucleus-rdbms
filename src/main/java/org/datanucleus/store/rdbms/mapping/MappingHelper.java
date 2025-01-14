/**********************************************************************
Copyright (c) 2009 Andy Jefferson and others. All rights reserved.
Licensed under the Apache License, Version 2.0 (the "License");
you may not use this file except in compliance with the License.
You may obtain a copy of the License at

    http://www.apache.org/licenses/LICENSE-2.0

Unless required by applicable law or agreed to in writing, software
distributed under the License is distributed on an "AS IS" BASIS,
WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
See the License for the specific language governing permissions and
limitations under the License.

Contributors:
    ...
**********************************************************************/
package org.datanucleus.store.rdbms.mapping;

import java.lang.reflect.Field;
import java.math.BigDecimal;
import java.sql.ResultSet;

import org.datanucleus.ClassLoaderResolver;
import org.datanucleus.ExecutionContext;
import org.datanucleus.FetchPlan;
import org.datanucleus.exceptions.NucleusException;
import org.datanucleus.identity.IdentityUtils;
import org.datanucleus.metadata.AbstractClassMetaData;
import org.datanucleus.metadata.AbstractMemberMetaData;
import org.datanucleus.metadata.ClassMetaData;
import org.datanucleus.state.DNStateManager;
import org.datanucleus.store.FieldValues;
import org.datanucleus.store.fieldmanager.FieldManager;
import org.datanucleus.store.rdbms.RDBMSStoreManager;
import org.datanucleus.store.rdbms.fieldmanager.ResultSetGetter;
import org.datanucleus.store.rdbms.mapping.java.JavaTypeMapping;
import org.datanucleus.store.rdbms.query.StatementClassMapping;
import org.datanucleus.store.rdbms.query.StatementMappingIndex;
import org.datanucleus.store.rdbms.table.DatastoreClass;
import org.datanucleus.store.types.converters.TypeConversionHelper;
import org.datanucleus.util.Localiser;
import org.datanucleus.util.NucleusLogger;

/**
 * Helper class for handling mappings.
 */
public class MappingHelper
{
    private MappingHelper(){}

    /**
     * Convenience method to return an array of positions for datastore columns for the supplied
     * mapping and the initial position value. For example if the mapping has a single datastore
     * column and the initial position is 1 then returns the array {1}.
     * @param initialPosition the initialPosition
     * @param mapping the Mapping
     * @return an array containing indexes for parameters
     */
    public static int[] getMappingIndices(int initialPosition, JavaTypeMapping mapping)
    {
        if (mapping.getNumberOfColumnMappings() == 0)
        {
            return new int[]{initialPosition};
        }

        int parameter[] = new int[mapping.getNumberOfColumnMappings()];
        for (int i=0; i<parameter.length; i++)
        {
            parameter[i] = initialPosition+i;
        }
        return parameter;
    }

    /**
     * Get the persistable object instance for a class using datastore identity defined by result set columns.
     * @param ec ExecutionContext
     * @param mapping The mapping in which this is returned
     * @param rs the ResultSet
     * @param resultIndexes indexes for the result set
     * @param cmd the AbstractClassMetaData
     * @return the persistable object
     */
    public static Object getObjectForDatastoreIdentity(ExecutionContext ec, JavaTypeMapping mapping, final ResultSet rs, int[] resultIndexes, AbstractClassMetaData cmd)
    {
        Object id = getDatastoreIdentityForResultSetRow(ec, mapping, rs, resultIndexes, cmd);
        if (id == null)
        {
            return null;
        }

        return ec.findObject(id, false, true, null);
    }

    /**
     * Get the datastore identity for the persistable object from the passed result set row.
     * @param ec ExecutionContext
     * @param mapping The mapping in which this is returned
     * @param rs the ResultSet
     * @param resultIndexes indexes for the result set
     * @param cmd the AbstractClassMetaData
     * @return the id
     */
    public static Object getDatastoreIdentityForResultSetRow(ExecutionContext ec, JavaTypeMapping mapping, final ResultSet rs, int[] resultIndexes, AbstractClassMetaData cmd)
    {
        // Datastore Identity - retrieve the datastore id "value" for the class.
        // Note that this is a temporary "id" that is simply formed from the type of base class in the relationship and the id value stored in the FK. 
        // The real "id" for the object may be of a different class. For that reason we get the object by checking the inheritance (3rd param in findObject())
        Object idValue = null;
        if (mapping.getNumberOfColumnMappings() > 0)
        {
            idValue = mapping.getColumnMapping(0).getObject(rs, resultIndexes[0]);
        }
        else
        {
            // 1-1 bidirectional "mapped-by" relation, so use ID mappings of related class to retrieve the value
            if (mapping.getReferenceMapping() != null) //TODO why is it null for PC concrete classes?
            {
                return mapping.getReferenceMapping().getObject(ec, rs, resultIndexes);
            }

            Class fieldType = mapping.getMemberMetaData().getType();
            JavaTypeMapping referenceMapping = mapping.getStoreManager().getDatastoreClass(fieldType.getName(), ec.getClassLoaderResolver()).getIdMapping();
            idValue = referenceMapping.getColumnMapping(0).getObject(rs, resultIndexes[0]);
        }

        if (idValue == null)
        {
            return null;
        }

        return ec.getNucleusContext().getIdentityManager().getDatastoreId(mapping.getType(), idValue);
    }

    /**
     * Get the application identity for the persistable instance from the passed result set row.
     * @param ec ExecutionContext
     * @param mapping The Java Type mapping for the instance
     * @param rs the ResultSet
     * @param resultIndexes indexes of the ResultSet for the PK field(s)
     * @param cmd the AbstractClassMetaData
     * @return the id
     */
    public static Object getApplicationIdentityForResultSetRow(final ExecutionContext ec, JavaTypeMapping mapping, final ResultSet rs, int[] resultIndexes, AbstractClassMetaData cmd)
    {
        // Check for null FK
        if (((RDBMSStoreManager)ec.getStoreManager()).getResultValueAtPosition(rs, mapping, resultIndexes[0]) == null)
        {
            // Assumption : if the first param is null, then the field is null
            return null;
        }

        // Abstract class
        if (((ClassMetaData)cmd).isAbstract() && cmd.getObjectidClass() != null)
        {
            return getObjectForAbstractClass(ec, mapping, rs, resultIndexes, cmd);
        }

        int totalFieldCount = cmd.getNoOfManagedMembers() + cmd.getNoOfInheritedManagedMembers();
        final StatementMappingIndex[] statementExpressionIndex = new StatementMappingIndex[totalFieldCount];

        ClassLoaderResolver clr = ec.getClassLoaderResolver();
        DatastoreClass datastoreClass = mapping.getStoreManager().getDatastoreClass(cmd.getFullClassName(), clr);
        final int[] pkFieldNumbers = cmd.getPKMemberPositions();

        int paramIndex = 0;
        for (int i=0; i<pkFieldNumbers.length; ++i)
        {
            AbstractMemberMetaData fmd = cmd.getMetaDataForManagedMemberAtAbsolutePosition(pkFieldNumbers[i]);
            JavaTypeMapping m = datastoreClass.getMemberMapping(fmd);
            statementExpressionIndex[fmd.getAbsoluteFieldNumber()] = new StatementMappingIndex(m);
            int expressionsIndex[] = new int[m.getNumberOfColumnMappings()];
            for (int j = 0; j < expressionsIndex.length; j++)
            {
                expressionsIndex[j] = resultIndexes[paramIndex++];
            }
            statementExpressionIndex[fmd.getAbsoluteFieldNumber()].setColumnPositions(expressionsIndex);
        }

        final StatementClassMapping resultMappings = new StatementClassMapping();
        for (int i=0;i<pkFieldNumbers.length;i++)
        {
            resultMappings.addMappingForMember(pkFieldNumbers[i], statementExpressionIndex[pkFieldNumbers[i]]);
        }

        final FieldManager resultsFM = new ResultSetGetter(ec, rs, resultMappings, cmd);
        return IdentityUtils.getApplicationIdentityForResultSetRow(ec, cmd, null, false, resultsFM);
    }

    /**
     * Get the persistent object instance for a class using application identity defined by the provided result set columns
     * @param ec ExecutionContext
     * @param mapping The mapping in which this is returned
     * @param rs the ResultSet
     * @param resultIndexes indexes in the result set to retrieve
     * @param cmd the AbstractClassMetaData
     * @return the persistent object instance
     */
    public static Object getObjectForApplicationIdentity(final ExecutionContext ec, JavaTypeMapping mapping, 
            final ResultSet rs, int[] resultIndexes, AbstractClassMetaData cmd)
    {
        ClassLoaderResolver clr = ec.getClassLoaderResolver();

        // Abstract class
        if (cmd instanceof ClassMetaData && ((ClassMetaData)cmd).isAbstract() && cmd.getObjectidClass() != null)
        {
            return getObjectForAbstractClass(ec, mapping, rs, resultIndexes, cmd);
        }

        // Create a ResultSetGetter with the data for the primary key column(s) of this class solely
        int totalMemberCount = cmd.getNoOfManagedMembers() + cmd.getNoOfInheritedManagedMembers();
        final StatementMappingIndex[] statementExpressionIndex = new StatementMappingIndex[totalMemberCount];

        DatastoreClass datastoreClass = mapping.getStoreManager().getDatastoreClass(cmd.getFullClassName(), clr);
        final int[] pkMemberPositions = cmd.getPKMemberPositions();
        int paramIndex = 0;
        for (int i=0; i<pkMemberPositions.length; ++i)
        {
            AbstractMemberMetaData mmd = cmd.getMetaDataForManagedMemberAtAbsolutePosition(pkMemberPositions[i]);
            JavaTypeMapping m = datastoreClass.getMemberMapping(mmd);
            statementExpressionIndex[mmd.getAbsoluteFieldNumber()] = new StatementMappingIndex(m);
            int expressionsIndex[] = new int[m.getNumberOfColumnMappings()];
            for (int j = 0; j < expressionsIndex.length; j++)
            {
                expressionsIndex[j] = resultIndexes[paramIndex++];
            }
            statementExpressionIndex[mmd.getAbsoluteFieldNumber()].setColumnPositions(expressionsIndex);
        }

        final StatementClassMapping resultMappings = new StatementClassMapping();
        for (int i=0;i<pkMemberPositions.length;i++)
        {
            resultMappings.addMappingForMember(pkMemberPositions[i], statementExpressionIndex[pkMemberPositions[i]]);
        }
        // TODO Use any other (non-PK) param values
        final FieldManager resultsFM = new ResultSetGetter(ec, rs, resultMappings, cmd);

        Object id = IdentityUtils.getApplicationIdentityForResultSetRow(ec, cmd, null, false, resultsFM);
        Class type = ec.getClassLoaderResolver().classForName(cmd.getFullClassName());
        return ec.findObject(id, new FieldValues()
        {
            public void fetchFields(DNStateManager sm)
            {
                sm.replaceFields(pkMemberPositions, resultsFM);
            }
            public void fetchNonLoadedFields(DNStateManager sm)
            {
                sm.replaceNonLoadedFields(pkMemberPositions, resultsFM);
            }
            public FetchPlan getFetchPlanForLoading()
            {
                return ec.getFetchPlan();
            }
        }, type, false, true);
    }

    /**
     * Create an object id instance and fill the fields using reflection
     * @param ec ExecutionContext
     * @param mapping Mapping in which this is returned
     * @param rs the ResultSet
     * @param resultIndexes indexes of the result set to use
     * @param cmd the AbstractClassMetaData
     * @return the id
     */
    protected static Object getObjectForAbstractClass(ExecutionContext ec, JavaTypeMapping mapping, final ResultSet rs, int[] resultIndexes, AbstractClassMetaData cmd)
    {
        ClassLoaderResolver clr = ec.getClassLoaderResolver();

        // Abstract class, so we need to generate an id before proceeding
        Class objectIdClass = clr.classForName(cmd.getObjectidClass());
        Object id = (cmd.usesSingleFieldIdentityClass()) ?
            createSingleFieldIdentity(ec, mapping, rs, resultIndexes, cmd, objectIdClass, clr.classForName(cmd.getFullClassName())) :
            createObjectIdentityUsingReflection(ec, mapping, rs, resultIndexes, cmd, objectIdClass);

        return ec.findObject(id, false, true, null);
    }

    /**
     * Create a SingleFieldIdentity instance
     * @param ec ExecutionContext
     * @param mapping Mapping in which this is returned
     * @param rs the ResultSet
     * @param resultIndexes the result set index(es)
     * @param cmd the AbstractClassMetaData
     * @param objectIdClass the object id class
     * @param pcClass the persistable class
     * @return the id
     */
    protected static Object createSingleFieldIdentity(ExecutionContext ec, JavaTypeMapping mapping, final ResultSet rs, int[] resultIndexes, AbstractClassMetaData cmd, 
            Class objectIdClass, Class pcClass)
    {
        int paramNumber = resultIndexes[0];
        try
        {
            Object idValue = mapping.getStoreManager().getResultValueAtPosition(rs, mapping, paramNumber);
            if (idValue == null)
            {
                throw new NucleusException(Localiser.msg("041039")).setFatal();
            }

            // Make sure the key type is correct for the type of SingleFieldIdentity
            idValue = TypeConversionHelper.convertTo(idValue, IdentityUtils.getKeyTypeForSingleFieldIdentityType(objectIdClass));
            return ec.getNucleusContext().getIdentityManager().getSingleFieldId(objectIdClass, pcClass, idValue);
        }
        catch (Exception e)
        {
            NucleusLogger.PERSISTENCE.error(Localiser.msg("041036", cmd.getObjectidClass(), e));
            return null;
        }
    }

    /**
     * Create a user-defined id instance and fill the fields using reflection.
     * @param ec ExecutionContext
     * @param mapping Mapping in which this is returned
     * @param rs the ResultSet
     * @param resultIndexes the result set index(es)
     * @param cmd the AbstractClassMetaData
     * @param objectIdClass the object id class
     * @return the id
     */
    protected static Object createObjectIdentityUsingReflection(ExecutionContext ec, JavaTypeMapping mapping, final ResultSet rs, int[] resultIndexes, AbstractClassMetaData cmd,
            Class objectIdClass)
    {
        Object fieldValue = null;
        try
        {
            // Create an application id instance
            Object id = objectIdClass.getDeclaredConstructor().newInstance();

            // Set the fields of the id
            int paramIndex = 0;
            int[] pkFieldNums = cmd.getPKMemberPositions();
            for (int i=0; i<pkFieldNums.length; ++i)
            {
                AbstractMemberMetaData fmd = cmd.getMetaDataForManagedMemberAtAbsolutePosition(pkFieldNums[i]);
                Field field = objectIdClass.getField(fmd.getName());

                JavaTypeMapping m = mapping.getStoreManager().getDatastoreClass(cmd.getFullClassName(), ec.getClassLoaderResolver()).getMemberMapping(fmd);
                // NOTE This assumes that each field has one datastore column.
                for (int j = 0; j < m.getNumberOfColumnMappings(); j++)
                {
                    Object obj = mapping.getStoreManager().getResultValueAtPosition(rs, mapping, resultIndexes[paramIndex++]);
                    if ((obj instanceof BigDecimal))
                    {
                        BigDecimal bigDecimal = (BigDecimal) obj;

                        // Oracle 10g returns BigDecimal for NUMBER columns, resulting in IllegalArgumentException when reflective setter is invoked for incompatible field type
                        Class keyType = IdentityUtils.getKeyTypeForSingleFieldIdentityType(field.getType());
                        obj = TypeConversionHelper.convertTo(bigDecimal, keyType);
                        if (!bigDecimal.subtract(new BigDecimal("" + obj)).equals(new BigDecimal("0")))
                        {
                            throw new NucleusException("Cannot convert retrieved BigInteger value to field of object id class!").setFatal();
                        }
                    }
                    // TODO field with multiple columns should have values returned from db merged here
                    fieldValue = obj;
                }
                field.set(id, fieldValue);
            }
            return id;
        }
        catch (Exception e)
        {
            AbstractMemberMetaData mmd = mapping.getMemberMetaData();
            NucleusLogger.PERSISTENCE.error(Localiser.msg("041037", cmd.getObjectidClass(), mmd == null ? null : mmd.getName(), fieldValue, e));
            return null;
        }
    }
}