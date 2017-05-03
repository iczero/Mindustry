package io.anuke.mindustry.ai;

import com.badlogic.gdx.ai.pfa.Connection;
import com.badlogic.gdx.ai.pfa.indexed.IndexedGraph;
import com.badlogic.gdx.utils.Array;

import io.anuke.mindustry.Moment;
import io.anuke.mindustry.world.Tile;

public class TileGraph implements IndexedGraph<Tile>{
	private Array<Connection<Tile>> tempConnections = new Array<Connection<Tile>>();

	@Override
	public Array<Connection<Tile>> getConnections(Tile fromNode){
		tempConnections.clear();
		
		if(fromNode.block().solid && fromNode != Moment.i.core)
			return tempConnections;
			
		for(Tile tile : fromNode.getNearby()){
			if(tile != null && (!tile.block().solid || tile == Moment.i.core))
				tempConnections.add(new TileConnection(fromNode, tile));
		}
		return tempConnections;
	}

	@Override
	public int getIndex(Tile node){
		return node.x+node.y*Moment.i.size;
	}

	@Override
	public int getNodeCount(){
		return Moment.i.size*Moment.i.size;
	}
}