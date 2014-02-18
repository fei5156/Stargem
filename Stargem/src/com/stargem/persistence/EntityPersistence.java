/**
 * 
 */
package com.stargem.persistence;

import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

import javax.naming.OperationNotSupportedException;

import com.badlogic.gdx.utils.Array;
import com.badlogic.gdx.utils.IntArray;
import com.badlogic.gdx.utils.ObjectMap;
import com.badlogic.gdx.utils.PooledLinkedList;
import com.stargem.Config;
import com.stargem.entity.ComponentFactory;
import com.stargem.entity.Entity;
import com.stargem.entity.EntityManager;
import com.stargem.entity.EntityRecycleObserver;
import com.stargem.entity.components.Component;
import com.stargem.entity.components.Physics;
import com.stargem.utils.Log;
import com.stargem.utils.StringHelper;

/**
 * EntityPersistence.java
 * 
 * @author Chris B
 * @date 12 Nov 2013
 * @version 1.0
 */
public class EntityPersistence implements EntityRecycleObserver, ConnectionListener {

	// the entity manager
	private final EntityManager em = EntityManager.getInstance();
	
	// the component types which should be managed
	private final Array<Class<? extends Component>> componentTypes = new Array<Class<? extends Component>>();

	// map Java datatypes to SQLite datatypes
	private ObjectMap<String, String> datatypes = new ObjectMap<String, String>();

	// the connection connection
	private Connection connection;
			
	// for keeping track of entities while loading
	private final IntArray entities = new IntArray();
	private int entityPointer;
	private int numEntities;

	// the list of entities to be deleted before the next save
	private final IntArray deathrow = new IntArray();
		
	public EntityPersistence() {
		this.datatypes = PersistenceManager.getInstance().getDatatypes();		
	}
	
	/**
	 * Add a component type for the entity persistence layer to manage.
	 * Types added will have a table created in the connection when the
	 * setup method is called. Load and Save calls will also use the
	 * component shape.
	 * 
	 * @param shape the component shape to manage.
	 */
	public void registerComponentType(Class<? extends Component> type) {
		componentTypes.add(type);
	}
		
	/**
	 * This method must be called before any entity loading can begin
	 * Populate entity list and reset the loading pointer. 
	 * Return the number of entities in the database
	 * 
	 * @return the number of entities to be loaded
	 */
	public int beginLoading() {
		this.entityPointer = 0;
		return populateEntityList();
	}

	/**
	 * Populate entity list and reset the pointer. Return the number of entities in the database
	 * @return the number of entities to be loaded
	 */
	private int populateEntityList() {
		numEntities = 0;
		StringBuilder sql = StringHelper.getBuilder();
		sql.append("SELECT entityId, (SELECT COUNT(entityId) FROM Entity) as numRows FROM Entity;");

		try {
			Statement statement = this.connection.createStatement();
			ResultSet result = statement.executeQuery(sql.toString());
						
			numEntities = result.getInt(2);
			
			while(result.next()) {
				entities.add(result.getInt(1));				
			}
			
			result.close();
			statement.close();
		}
		catch (SQLException e) {
			Log.error(Config.SQL_ERR, e.getMessage());
		}
		
		return numEntities;
	}

	/**
	 * Returns the current entity id and increments the pointer
	 * @return the current entity id
	 */
	private int nextId() {
		int entityId = this.entities.get(entityPointer);
		entityPointer += 1;
		return entityId;
	}

	/**
	 * @param e
	 * @throws OperationNotSupportedException 
	 */
	public void loadPlayerEntity(Entity e) {
		
		// copy only the physics component from the database
		
		// first remove the physics component
		this.em.removeComponent(e, Physics.class);
		
		// then add the new component
		this.loadComponent(e, Physics.class);
	}
	
	/**
	 * Populate the given entity with an ID and components from the database.
	 * The given entity should be a blank instance with no attached components or ID.
	 * 
	 * @param entity a blank entity with no components or ID
	 */
	public void loadEntity(Entity entity) {

		if (numEntities == 0) {
			throw new Error("Cannot load entity before populateEntityList has been called.");
		}

		if (entity.getId() != 0) {
			throw new Error("The entity cannot be loaded because it already has an ID.");
		}

		entity.setId(nextId());
		
		Log.info(Config.SQL_ERR, "Loading entity: " + entity.getId());

		// iterate over component types and load them
		for (Class<? extends Component> type : componentTypes) {
			loadComponent(entity, type);
		}

	}

	/**
	 * Load a component of the given type and attach it to the entity given.
	 * If no component is found for this entity in the database none it attached.
	 * 
	 * @param entity the entity to attach the loaded component to.
	 * @param shape the class shape of the component to load.
	 */
	private void loadComponent(Entity entity, Class<? extends Component> type) {
		
		// leave early if there is no component of shape for this entity
		StringBuilder sql = StringHelper.getBuilder();
		sql.append("SELECT COUNT(entityId) FROM ");
		sql.append(type.getSimpleName());
		sql.append(" WHERE entityId=");
		sql.append(entity.getId());
		sql.append(";");

		int numRows = 0;

		try {
			Statement statement = this.connection.createStatement();
			ResultSet result = statement.executeQuery(sql.toString());
			numRows = result.getInt(1);
			
			result.close();
			statement.close();
		}
		catch (SQLException e) {
			Log.error(Config.SQL_ERR, e.getMessage() + " while creating the " + type.getSimpleName() + " table: " + sql.toString());
		}

		if (numRows == 0) {
			//Log.info(Config.SQL_ERR, "No component of type: " + type.getSimpleName());
			return;
		}
		else {
			Log.info(Config.SQL_ERR, "Loading component " + type.getSimpleName() + " for entity " + entity.getId());
		}

		// select the data from the correct table
		sql.setLength(0);
		sql.append("SELECT * FROM ");
		sql.append(type.getSimpleName());
		sql.append(" WHERE entityId=");
		sql.append(entity.getId());
		sql.append(";");

		try {

			// select the component data
			Statement statement = this.connection.createStatement();
			ResultSet result = statement.executeQuery(sql.toString());

			// use reflection to create a new component

			// get fields of the component class shape and create an array of types
			// for grabbing the correct method from the component factory
			// add an extra field which is the entity send to the factory
			Field[] fields = type.getFields();
			Class<?>[] fieldTypes = new Class<?>[fields.length + 1];
			fieldTypes[0] = entity.getClass();
			for (int i = 0, n = fields.length; i < n; i += 1) {
				fieldTypes[i + 1] = fields[i].getType();
			}

			// get data ready for creating the component
			// we start on column 2 because we want to skip the entityId column
			// we add one extra field for the entity which is also sent as an argument
			Object[] arguments = new Object[fields.length + 1];
			arguments[0] = entity;
			for (int i = 1, n = arguments.length; i < n; i += 1) {
				arguments[i] = this.getArgument(fieldTypes[i], result.getObject(i + 1));				
			}

			result.close();
			statement.close();
			
			// get the factory method which instantiates the component and call it, 
			// then add the component to the entity 
			try {
				
//				Log.info(Config.REFLECTION_ERR, type.getSimpleName().toLowerCase());
//				Log.info(Config.REFLECTION_ERR, "Args num: " + arguments.length);
//				Log.info(Config.REFLECTION_ERR, "Fields num: " + fieldTypes.length);
//				for(int i = 0, n = fieldTypes.length; i < n; i += 1) {
//					Log.info(Config.REFLECTION_ERR, "Argument: " + fieldTypes[i].getSimpleName() + " " + arguments[i].getClass().getSimpleName());
//				}
				
				Method method = ComponentFactory.class.getMethod(type.getSimpleName().toLowerCase(), fieldTypes);
				Component component = (Component) method.invoke(null, arguments);
				em.addComponent(entity, component);
			}
			catch (NoSuchMethodException e) {
				Log.error(Config.REFLECTION_ERR, e.getMessage() + ": No method found in component factory, unable to create new component.");
			}
			catch (SecurityException e) {
				Log.error(Config.REFLECTION_ERR, e.getMessage() + ": Unable to create new component.");
			}
			catch (IllegalAccessException e) {
				Log.error(Config.REFLECTION_ERR, e.getMessage());
			}
			catch (IllegalArgumentException e) {
				Log.error(Config.REFLECTION_ERR, e.getMessage());
			}
			catch (InvocationTargetException e) {
				Log.error(Config.REFLECTION_ERR, e.getMessage());
			}

		}
		catch (SQLException e) {
			Log.error(Config.SQL_ERR, e.getMessage() + " while accessing the " + type.getSimpleName() + " table: " + sql.toString());
		}
	}

	/**
	 * @param type
	 * @param object
	 * @return
	 */
	private Object getArgument(Class<?> type, Object argument) {
		
		// if the field type is boolean then we want to convert from
		// the integer value 1/0 stored in the database to boolean true/false
		if(type.equals(boolean.class)) {					
			if((Integer)argument == 1) {
				argument = true;
			}
			else {
				argument = false;
			}
		}
		
		// the database gives us a double when we need a float
		// we need to convert from a double to a float
		else if(type.equals(float.class)) {
			Double d = (Double)argument;
			Float f = new Float(d.floatValue());
			argument = f;
		}
		
		return argument;
	}

	/**
	 * Iterate over all entities in the entity manager storing
	 * them and their components in the database.
	 */
	public void save() {
		
		// delete all recycled entities from the database
		for(int i = 0, n = this.deathrow.size; i < n; i += 1) {
			this.deleteEntity(this.deathrow.get(i));
		}
		this.deathrow.clear();
		
		// save all entities in the manager to the database
		for(Entity entity : em.getAllEntities()) {
			this.storeEntity(entity);
		}
	}

	/**
	 * Remove the entity and all its components from the database
	 * 
	 * @param entityId
	 */
	private void deleteEntity(int entityId) {
		
		Log.info(Config.SQL_ERR, "Deleting entity " + entityId);
		
		// remove the entity from the entity table
		StringBuilder sql = StringHelper.getBuilder();
		sql.append("DELETE FROM Entity WHERE entityId=");
		sql.append(entityId);
		sql.append(";");

		try {
			Statement statement = this.connection.createStatement();
			statement.executeUpdate(sql.toString());
			statement.close();
		}
		catch (SQLException e) {
			Log.error(Config.SQL_ERR, e.getMessage() + " while deleting entity: " + sql.toString());
		}
		
		// iterate over component types and load them
		for (Class<? extends Component> type : componentTypes) {
			deleteComponent(entityId, type);
		}
		
	}

	/**
	 * Remove the component of the given shape from the given entity.
	 * 
	 * @param entityId the entity
	 * @param shape the shape of the component
	 */
	private void deleteComponent(int entityId, Class<? extends Component> type) {
		
		Log.info(Config.SQL_ERR, "Deleting component " + type.getSimpleName() + " from entity " + entityId);
		
		StringBuilder sql = StringHelper.getBuilder();
		sql.append("DELETE FROM ");
		sql.append(type.getSimpleName());
		sql.append(" WHERE entityId=");
		sql.append(entityId);
		sql.append(";");
		
		try {
			Statement statement = this.connection.createStatement();
			statement.executeUpdate(sql.toString());
			statement.close();
		}
		catch (SQLException e) {
			Log.error(Config.SQL_ERR, e.getMessage() + " while deleting " + type.getSimpleName() + " component: " + sql.toString());
		}
	}

	/**
	 * Store the given entity and all its components in the connection.
	 * If the entity id is zero then it has not been previously stored in the connection and
	 * will be inserted, otherwise it will be updated.
	 * 
	 * @param entity the entity to store.
	 */
	private void storeEntity(Entity entity) {

		Log.info(Config.SQL_ERR, "Storing entity " + entity.getId());
				
		StringBuilder sql = StringHelper.getBuilder();
		PooledLinkedList<? extends Component> components;

		// if we have a zero id then this entity has not been stored before
		if (entity.getId() == 0) {
			// store the entity in the entity table and get it's new id
			sql.append("INSERT INTO Entity DEFAULT VALUES;");

			try {
				Statement statement = this.connection.createStatement();
				statement.executeUpdate(sql.toString());

				// get the id of the entity
				int id = statement.getGeneratedKeys().getInt(1);
				entity.setId(id);
				
				statement.close();
				
				Log.info(Config.SQL_ERR, "Assigning a new id: " + id);		
			}
			catch (SQLException e) {
				Log.error(Config.SQL_ERR, e.getMessage() + " while inserting new entity: " + sql.toString());
			}

			// insert all the components for this entity into the connection
			components = em.getComponents(entity);
			Component c = null;
			for(components.iter(); (c = components.next()) != null;) {
				this.insertComponent(entity, c);
			}
		}
		else {
			// update all the components for this entity
			components = em.getComponents(entity);
			Component c = null;
			for(components.iter(); (c = components.next()) != null;) {
				this.updateComponent(entity, c);
			}
		}
	}

	/**
	 * Insert a component into the database
	 * 
	 * @param entity
	 * @param component
	 */
	private void insertComponent(Entity entity, Component component) {

		Log.info(Config.SQL_ERR, "Inserting component " + component.getClass().getSimpleName() + " from entity " + entity.getId());
				
		StringBuilder sql = StringHelper.getBuilder();

		// generate the sql from the components fields
		Class<? extends Component> type = component.getClass();
		Field[] fields = type.getFields();

		sql.append("INSERT INTO ");
		sql.append(type.getSimpleName());
		sql.append(" VALUES (");
		sql.append(entity.getId());

		for (int i = 0, n = fields.length; i < n; i += 1) {

			Field f = fields[i];
			String datatype = f.getType().getSimpleName();

			// if this is a recognised datatype then we can update it
			if (this.datatypes.containsKey(datatype)) {
				try {
					// adding quotes to deal with strings and chars
					// other data types don't seem to mind this
					sql.append(", ");
					sql.append("\"");
					sql.append(f.get(component)); // get the field data
					sql.append("\"");
				}
				catch (IllegalArgumentException e) {
					Log.error(Config.SQL_ERR, e.getMessage());
				}
				catch (IllegalAccessException e) {
					Log.error(Config.SQL_ERR, e.getMessage());
				}
			}
			else {
				//Log.echo("Some kind of recursive call to store the complex field?");
				throw new Error("Unknown shape: " + datatype + " can only store primitives and Strings.");
			}
		}
		sql.append(");");

		// run the query
		try {
			Statement statement = this.connection.createStatement();
			statement.executeUpdate(sql.toString());
			statement.close();
		}
		catch (SQLException e) {
			Log.error(Config.SQL_ERR, e.getMessage() + " while inserting into the " + type.getSimpleName() + " table: " + sql.toString());
		}
	}

	/**
	 * Update a component in the database
	 * 
	 * @param entity
	 * @param component
	 */
	private void updateComponent(Entity entity, Component component) {

		Log.info(Config.SQL_ERR, "Updating component " + component.getClass().getSimpleName() + " from entity " + entity.getId());
		
		StringBuilder sql = StringHelper.getBuilder();

		// generate the sql from the components fields
		Class<? extends Component> type = component.getClass();
		Field[] fields = type.getFields();

		sql.append("UPDATE ");
		sql.append(type.getSimpleName());
		sql.append(" SET entityId=");
		sql.append(entity.getId());

		for (int i = 0, n = fields.length; i < n; i += 1) {

			Field f = fields[i];
			String datatype = f.getType().getSimpleName();

			// if this is a recognised datatype then we can update it
			if (this.datatypes.containsKey(datatype)) {
				try {
					// adding quotes to deal with strings and chars
					// other data types don't seem to mind this
					sql.append(", ");
					sql.append(f.getName()); // get the field name
					sql.append("=\"");
					sql.append(f.get(component)); // get the field data
					sql.append("\"");
				}
				catch (IllegalArgumentException e) {
					Log.error(Config.SQL_ERR, e.getMessage());
				}
				catch (IllegalAccessException e) {
					Log.error(Config.SQL_ERR, e.getMessage());
				}
			}
			else {
				//Log.echo("Some kind of recursive call to store the complex field?");
				throw new Error("Unknown shape: " + datatype + " can only store primitives and Strings.");
			}
		}
		sql.append(") WHERE entityId=");
		sql.append(entity.getId());
		sql.append(";");

		// run the query
		try {
			Statement statement = this.connection.createStatement();
			statement.executeUpdate(sql.toString());
			statement.close();
		}
		catch (SQLException e) {
			Log.error(Config.SQL_ERR, e.getMessage() + " while updating the " + type.getSimpleName() + " table: " + sql.toString());
		}
	}

	/**
	 * Called by the entity manager when an entity is recycled. This method
	 * queues up an entity to be deleted from the connection before the next save.
	 * It is important to do all database updates in a block so that in the event
	 * of a crash entities are not missing from the previous save. 
	 * We use the id instead of the entity because the entity instance has been 
	 * stripped of its ID. 
	 * 
	 */
	@Override
	public void recycle(int entityId) {		
		// add the entity to the deletion list
		this.deathrow.add(entityId);		
	}

	/* (non-Javadoc)
	 * @see com.stargem.persistence.ConnectionListener#setConnection(java.sql.Connection)
	 */
	@Override
	public void setConnection(Connection c) {
		this.connection = c;
	}

	/**
	 * Import the entity and component tables from the database at the 
	 * path given into the currently active profile. This is done as part 
	 * of the level changing process.
	 * 
	 * @param campaignName
	 * @param levelName
	 */
	protected void importEntities(String databasePath) {
		
		// attach the database for the selected world
		String attachName = "world";
		SQLHelper.attach(connection, databasePath, attachName);		
		
		// for the entity table copy the entries
		String toTableName = "main.Entity";
		String fromTableName = "\"" + attachName + "\"" + ".Entity";
		
		SQLHelper.dropTable(connection, toTableName);
		SQLHelper.createAs(connection, fromTableName, toTableName);	
		
		// for each component shape copy the table entries		
		for (Class<? extends Component> type : componentTypes) {			
			toTableName = "main." + type.getSimpleName();
			fromTableName = "\"" + attachName + "\"" + "." + type.getSimpleName();
			
			SQLHelper.dropTable(connection, toTableName);
			SQLHelper.createAs(connection, fromTableName, toTableName);		
		}
		
		// detach the database
		SQLHelper.detach(connection, attachName);
						
	}
}