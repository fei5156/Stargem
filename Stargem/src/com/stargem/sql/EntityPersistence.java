/**
 * 
 */
package com.stargem.sql;

import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Iterator;

import com.badlogic.gdx.utils.Array;
import com.badlogic.gdx.utils.IntArray;
import com.badlogic.gdx.utils.ObjectMap;
import com.badlogic.gdx.utils.PooledLinkedList;
import com.stargem.Config;
import com.stargem.Log;
import com.stargem.StringHelper;
import com.stargem.entity.ComponentFactory;
import com.stargem.entity.Entity;
import com.stargem.entity.EntityManager;
import com.stargem.entity.EntityRecycleObserver;
import com.stargem.entity.components.Component;

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

	// a statement for executing sql queries
	private Statement statement;
	
	// a result set for holding results
	private ResultSet result;
		
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
	 * component type.
	 * 
	 * @param type the component type to manage.
	 */
	public void registerComponentType(Class<? extends Component> type) {
		componentTypes.add(type);
	}
		
	/**
	 * This method must be called before any entity loading can begin
	 * 
	 * @return
	 */
	public int beginLoading() {
		return populateEntityList();
	}

	/**
	 * Populate entity list and reset the pointer. Return the number of entities in the connection
	 * @return the number of entities to be loaded
	 */
	private int populateEntityList() {
		numEntities = 0;
		StringBuilder sql = StringHelper.getBuilder();
		sql.append("SELECT entityId, (SELECT COUNT(entityId) FROM Entity) as numRows FROM Entity;");

		try {
			this.statement = this.connection.createStatement();
			this.result = this.statement.executeQuery(sql.toString());
						
			numEntities = result.getInt(2);
			
			while(result.next()) {
				entities.add(result.getInt(1));				
			}
			
			this.result.close();
			this.statement.close();
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
	 * Populate the given entity with an ID and components from the connection.
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
	 * If no component is found for this entity in the connection none it attached.
	 * 
	 * @param entity the entity to attach the loaded component to.
	 * @param type the class type of the component to load.
	 */
	private void loadComponent(Entity entity, Class<? extends Component> type) {

		Log.info(Config.SQL_ERR, "Loading component " + type.getSimpleName() + " for entity " + entity.getId());
		
		// leave early if there is no component of type for this entity
		StringBuilder sql = StringHelper.getBuilder();
		sql.append("SELECT COUNT(entityId) FROM ");
		sql.append(type.getSimpleName());
		sql.append(" WHERE entityId=");
		sql.append(entity.getId());
		sql.append(";");

		int numRows = 0;

		try {
			this.statement = this.connection.createStatement();
			this.result = this.statement.executeQuery(sql.toString());
			numRows = result.getInt(1);
			
			this.result.close();
			this.statement.close();
		}
		catch (SQLException e) {
			Log.error(Config.SQL_ERR, e.getMessage() + " while creating the " + type.getSimpleName() + " table: " + sql.toString());
		}

		if (numRows == 0) {
			Log.info(Config.SQL_ERR, "No component of type: " + type.getSimpleName());
			return;
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
			this.statement = this.connection.createStatement();
			ResultSet result = this.statement.executeQuery(sql.toString());

			// use reflection to create a new component

			// get fields of the component class type and create an array of types
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

				// TODO see if this works with all data types
				// if not then maybe have to some conditionals
				// maybe boolean won't work?
				arguments[i] = result.getObject(i + 2);
			}

			this.result.close();
			this.statement.close();
			
			// get the factory method which instantiates the component and call it, 
			// then add the component to the entity 
			try {
				Method method = ComponentFactory.class.getMethod(type.getSimpleName().toLowerCase(), fieldTypes);
				Component component = (Component) method.invoke(null, arguments);
				em.addComponent(entity, component);
			}
			catch (NoSuchMethodException e) {
				Log.error(Config.SQL_ERR, e.getMessage() + ": No method found in component factory, unable to create new component.");
			}
			catch (SecurityException e) {
				Log.error(Config.SQL_ERR, e.getMessage() + ": Unable to create new component.");
			}
			catch (IllegalAccessException e) {
				Log.error(Config.SQL_ERR, e.getMessage());
			}
			catch (IllegalArgumentException e) {
				Log.error(Config.SQL_ERR, e.getMessage());
			}
			catch (InvocationTargetException e) {
				Log.error(Config.SQL_ERR, e.getMessage());
			}

		}
		catch (SQLException e) {
			Log.error(Config.SQL_ERR, e.getMessage() + " while accessing the " + type.getSimpleName() + " table: " + sql.toString());
		}
	}

	/**
	 * Iterate over all entities in the entity manager storing
	 * them and their components in the connection.
	 */
	public void save() {
		
		// delete all recycled entities from the connection
		for(int i = 0, n = this.deathrow.size; i < n; i += 1) {
			this.deleteEntity(this.deathrow.get(i));
		}
		this.deathrow.clear();
		
		// save all entities in the manager
		Iterator<Entity> entities = em.getAllEntities();
		Entity entity;
		while (entities.hasNext()) {
			entity = entities.next();
			this.storeEntity(entity);
		}
	}

	/**
	 * Remove the entity and all its components from the connection
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
			this.statement = this.connection.createStatement();
			this.statement.executeUpdate(sql.toString());
			this.statement.close();
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
	 * Remove the component of the given type from the given entity.
	 * 
	 * @param entityId the entity
	 * @param type the type of the component
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
			this.statement = this.connection.createStatement();
			this.statement.executeUpdate(sql.toString());
			this.statement.close();
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
				this.statement = this.connection.createStatement();
				this.statement.executeUpdate(sql.toString());

				// get the id of the entity
				int id = this.statement.getGeneratedKeys().getInt(1);
				entity.setId(id);
				
				this.statement.close();
				
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
	 * Insert a component into the connection
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
				throw new Error("Unknown type: " + datatype + " can only store primitives and Strings.");
			}
		}
		sql.append(");");

		// run the query
		try {
			this.statement = this.connection.createStatement();
			this.statement.executeUpdate(sql.toString());
			this.statement.close();
		}
		catch (SQLException e) {
			Log.error(Config.SQL_ERR, e.getMessage() + " while inserting into the " + type.getSimpleName() + " table: " + sql.toString());
		}
	}

	/**
	 * Update a component in the connection
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
				throw new Error("Unknown type: " + datatype + " can only store primitives and Strings.");
			}
		}
		sql.append(") WHERE entityId=");
		sql.append(entity.getId());
		sql.append(";");

		// run the query
		try {
			this.statement = this.connection.createStatement();
			this.statement.executeUpdate(sql.toString());
			this.statement.close();
		}
		catch (SQLException e) {
			Log.error(Config.SQL_ERR, e.getMessage() + " while updating the " + type.getSimpleName() + " table: " + sql.toString());
		}
	}

	/**
	 * Called by the entity manager when an entity is recycled. This method
	 * queues up an entity to be deleted from the connection before the next save.
	 * It is important to do all connection updates in a block so that in the event
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
	 * @see com.stargem.sql.ConnectionListener#setConnection(java.sql.Connection)
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
		
		// for each component type copy the table entries		
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