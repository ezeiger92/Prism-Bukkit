package me.botsko.prism.actionlibs;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import java.util.concurrent.LinkedBlockingQueue;

import org.bukkit.GameMode;
import org.bukkit.entity.Player;

import me.botsko.prism.Prism;
import me.botsko.prism.actions.Action;

public class ActionRecorder implements Runnable {
	
	/**
	 * 
	 */
	private Prism plugin;
	
	/**
	 * 
	 */
	private static final LinkedBlockingQueue<Action> queue = new LinkedBlockingQueue<Action>();
	
	/**
	 * 
	 */
	private final List<String> ignore_players;
	
	/**
	 * 
	 */
	private final List<String> ignore_worlds;
	
	/**
	 * 
	 */
	private final boolean ignore_creative;
	
	/**
	 * 
	 */
	private Connection conn = null;
	
	
	/**
	 * 
	 * @param plugin
	 */
	@SuppressWarnings("unchecked")
	public ActionRecorder( Prism plugin ){
		this.plugin = plugin;
		ignore_players = (List<String>) plugin.getConfig().getList( "prism.ignore.players" );
		ignore_worlds = (List<String>) plugin.getConfig().getList( "prism.ignore.worlds" );
		ignore_creative = plugin.getConfig().getBoolean( "prism.ignore.players-in-creative" );
	}
	
	
	/**
	 * 
	 */
	public int getQueueSize(){
		return queue.size();
	}
	
	
	/**
	 * 
	 * @param a
	 */
	public void addToQueue( Action a ){
		
		if(a == null) return;
		
		// Verify we're expected to track this action, world, player
		if(!shouldTrack(a)){
			return;
		}
		
		queue.add(a);
		
		if(a.getData().length() > 255){
			plugin.log("Error: Data exceeds allowed length and will not be logged. Please inform Prism developers: " + a.getData());
		}
	}
	
	
	/**
	 * 
	 * @param a
	 * @return
	 */
	protected boolean shouldTrack( Action a ){
		
		// Always track Prism actions - it's mainly internal
		// use anyway.
		if(a.getType().getActionType().contains("prism")){
			return true;
		}
		
		// Should we ignore this player?
		if(ignore_players != null && ignore_players.contains( a.getPlayer_name() )){
			return false;
		}
		
		// Should we ignore this world?
		if(ignore_worlds != null && ignore_worlds.contains( a.getWorld_name() )){
			return false;
		}
		
		// Should we ignore this action type?
		String action_type = a.getType().getActionType();
		if(!plugin.getConfig().getBoolean( "prism.tracking." + action_type )){
			return false;
		}
		
		// Should we ignore this player for being in creative?
		// @todo maybe we should pass the full player to actions.
		if( ignore_creative ){
			String name = a.getPlayer_name();
			if(name == null) return true;
			Player pl = plugin.getServer().getPlayer( name );
			if(pl != null && pl.getGameMode().equals(GameMode.CREATIVE)){
				return false;
			}
		}
		return true;
	}
	
	
	/**
	 * 
	 */
	public void save(){
		if(!queue.isEmpty()){
			insertActionsIntoDatabase();
		}
	}
	
	
	/**
	 * 
	 * @param a
	 */
	public int insertActionIntoDatabase( Action a){
		int id = 0;
		try {
			dbc();
			if(conn == null){
				plugin.log("Prism database error. Connection should be there but it's not. This action wasn't logged.");
				return 0;
			}
	        PreparedStatement s = conn.prepareStatement("INSERT INTO prism_actions (action_time,action_type,player,world,x,y,z,data) VALUES (NULL,?,?,?,?,?,?,?)", Statement.RETURN_GENERATED_KEYS);
	        s.setString(1,a.getType().getActionType());
	        s.setString(2,a.getPlayer_name());
	        s.setString(3,a.getWorld_name());
	        s.setInt(4,(int)a.getX());
	        s.setInt(5,(int)a.getY());
	        s.setInt(6,(int)a.getZ());
	        s.setString(7,a.getData());
	        s.executeUpdate();
	        
	        ResultSet generatedKeys = s.getGeneratedKeys();
	        if(generatedKeys.next()){
	        	id = generatedKeys.getInt(1);
	        }
	        
    		s.close();
            conn.close();
        } catch (SQLException e) {
        	plugin.log("Database connection error: " + e.getMessage());
	        e.printStackTrace();
        }
		return id;
	}
	
	
	/**
	 * 
	 * @param entities
	 * @throws SQLException
	 */
	public void insertActionsIntoDatabase() {
	    PreparedStatement s = null;
	    int actionsRecorded = 0;
	    try {
	    	
	        final Connection conn = plugin.getDbConnection();
	        if(conn == null || conn.isClosed()){
				plugin.log("Prism database error. Connection should be there but it's not. Leaving actions to log in queue.");
				return;
			}
	        conn.setAutoCommit(false);
	        s = conn.prepareStatement("INSERT INTO prism_actions (action_time,action_type,player,world,x,y,z,data) VALUES (NULL,?,?,?,?,?,?,?)");
	        int i = 0;
	        while (!queue.isEmpty()){
	        	actionsRecorded++;
	        	Action a = queue.poll();
	        	if(a == null) continue;
		        s.setString(1,a.getType().getActionType());
		        s.setString(2,a.getPlayer_name());
		        s.setString(3,a.getWorld_name());
		        s.setInt(4,(int)a.getX());
		        s.setInt(5,(int)a.getY());
		        s.setInt(6,(int)a.getZ());
		        s.setString(7,a.getData());
	            s.addBatch();
	            if ((i + 1) % 1000 == 0) {
	                s.executeBatch(); // Execute every 1000 items.
	            }
	            i++;
	        }
	        
	        // Save the current count to the queue for short historical data
	        plugin.queueStats.addRunCount(actionsRecorded);
	        
	        s.executeBatch();
	        conn.commit();
	        s.close();
            conn.close();
	    } catch (SQLException e) {
	    	plugin.log("Database connection error: " + e.getMessage());
	        e.printStackTrace();
        }
	}


	/**
	 * 
	 */
	@Override
	public void run() {
		save();
	}
	
	
	/**
	 * 
	 */
	protected void dbc(){
		try {
			if (conn == null || conn.isClosed() || !conn.isValid(1)) {
				conn = plugin.getDbConnection();
			}
		} catch (SQLException e) {
			plugin.log("Database connection error: " + e.getMessage());
	        e.printStackTrace();
		}
	}
}
