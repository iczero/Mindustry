package io.anuke.mindustry.core;

import com.badlogic.gdx.math.GridPoint2;
import com.badlogic.gdx.utils.Array;
import com.badlogic.gdx.utils.ObjectMap;
import io.anuke.mindustry.ai.BlockIndexer;
import io.anuke.mindustry.ai.Pathfinder;
import io.anuke.mindustry.content.blocks.Blocks;
import io.anuke.mindustry.core.GameState.State;
import io.anuke.mindustry.game.EventType.TileChangeEvent;
import io.anuke.mindustry.game.EventType.WorldLoadEvent;
import io.anuke.mindustry.game.Team;
import io.anuke.mindustry.io.MapIO;
import io.anuke.mindustry.maps.*;
import io.anuke.mindustry.world.Block;
import io.anuke.mindustry.world.Tile;
import io.anuke.mindustry.maps.generation.WorldGenerator;
import io.anuke.ucore.core.Events;
import io.anuke.ucore.core.Timers;
import io.anuke.ucore.entities.EntityPhysics;
import io.anuke.ucore.modules.Module;
import io.anuke.ucore.util.Log;
import io.anuke.ucore.util.Mathf;
import io.anuke.ucore.util.ThreadArray;
import io.anuke.ucore.util.Tmp;

import static io.anuke.mindustry.Vars.*;

public class World extends Module{
    private Map currentMap;
    private Sector currentSector;
    private Tile[][] tiles;
    private Pathfinder pathfinder = new Pathfinder();
    private BlockIndexer indexer = new BlockIndexer();
    private Maps maps = new Maps();
    private Sectors sectors = new Sectors();
    private WorldGenerator generator = new WorldGenerator();

    private Array<Tile> tempTiles = new ThreadArray<>();
    private boolean generating, invalidMap;

    public World(){
        maps.load();
    }

    @Override
    public void init(){
        sectors.load();
    }

    @Override
    public void dispose(){
        maps.dispose();
    }

    public WorldGenerator generator(){
        return generator;
    }

    public Sectors sectors(){
        return sectors;
    }

    public Maps maps(){
        return maps;
    }

    public BlockIndexer indexer(){
        return indexer;
    }

    public Pathfinder pathfinder(){
        return pathfinder;
    }

    public boolean isInvalidMap(){
        return invalidMap;
    }

    public boolean solid(int x, int y){
        Tile tile = tile(x, y);

        return tile == null || tile.solid();
    }

    public boolean passable(int x, int y){
        Tile tile = tile(x, y);

        return tile != null && tile.passable();
    }

    public boolean wallSolid(int x, int y){
        Tile tile = tile(x, y);
        return tile == null || tile.block().solid;
    }

    public boolean isAccessible(int x, int y){
        return !wallSolid(x, y - 1) || !wallSolid(x, y + 1) || !wallSolid(x - 1, y) || !wallSolid(x + 1, y);
    }

    public boolean floorBlends(int x, int y, Block block){
        Tile tile = tile(x, y);
        return tile == null || tile.floor().id <= block.id;
    }

    public Map getMap(){
        return currentMap;
    }

    public Sector getSector(){
        return currentSector;
    }

    public void setSector(Sector currentSector){
        this.currentSector = currentSector;
    }

    public void setMap(Map map){
        this.currentMap = map;
    }

    public int width(){
        return tiles == null ? 0 : tiles.length;
    }

    public int height(){
        return tiles == null ? 0 : tiles[0].length;
    }

    public int toPacked(int x, int y){
        return x + y * width();
    }

    public Tile tile(int packed){
        return tiles == null ? null : tile(packed % width(), packed / width());
    }

    public Tile tile(int x, int y){
        if(tiles == null){
            return null;
        }
        if(!Mathf.inBounds(x, y, tiles)) return null;
        return tiles[x][y];
    }

    public Tile rawTile(int x, int y){
        return tiles[x][y];
    }

    public Tile tileWorld(float x, float y){
        return tile(Mathf.scl2(x, tilesize), Mathf.scl2(y, tilesize));
    }

    public int toTile(float coord){
        return Mathf.scl2(coord, tilesize);
    }

    public Tile[][] getTiles(){
        return tiles;
    }

    private void clearTileEntities(){
        for(int x = 0; x < tiles.length; x++){
            for(int y = 0; y < tiles[0].length; y++){
                if(tiles[x][y] != null && tiles[x][y].entity != null){
                    tiles[x][y].entity.remove();
                }
            }
        }
    }

    /**
     * Resizes the tile array to the specified size and returns the resulting tile array.
     * Only use for loading saves!
     */
    public Tile[][] createTiles(int width, int height){
        if(tiles != null){
            clearTileEntities();

            if(tiles.length != width || tiles[0].length != height){
                tiles = new Tile[width][height];
            }
        }else{
            tiles = new Tile[width][height];
        }

        return tiles;
    }

    /**
     * Call to signify the beginning of map loading.
     * TileChangeEvents will not be fired until endMapLoad().
     */
    public void beginMapLoad(){
        generating = true;
    }

    /**
     * Call to signify the end of map loading. Updates tile occlusions and sets up physics for the world.
     * A WorldLoadEvent will be fire.
     */
    public void endMapLoad(){
        for(int x = 0; x < tiles.length; x++){
            for(int y = 0; y < tiles[0].length; y++){
                tiles[x][y].updateOcclusion();

                if(tiles[x][y].entity != null){
                    tiles[x][y].entity.updateProximity();
                }
            }
        }

        EntityPhysics.resizeTree(0, 0, tiles.length * tilesize, tiles[0].length * tilesize);

        generating = false;
        Events.fire(WorldLoadEvent.class);
    }

    /**Loads up a sector map. This does not call play(), but calls reset().*/
    public void loadSector(Sector sector){
        currentSector = sector;
        state.mode = sector.missions.peek().getMode();
        state.difficulty = sector.getDifficulty();
        Timers.mark();
        Timers.mark();

        logic.reset();

        beginMapLoad();

        int width = sectorSize * sector.size, height = sectorSize * sector.size;

        Tile[][] tiles = createTiles(width, height);

        Map map = new Map("Sector " + sector.x + ", " + sector.y, new MapMeta(0, new ObjectMap<>(), width, height, null), true, () -> null);
        setMap(map);

        EntityPhysics.resizeTree(0, 0, width * tilesize, height * tilesize);

        generator.generateMap(tiles, sector);

        endMapLoad();
    }

    public void loadMap(Map map){
        currentSector = null;
        beginMapLoad();
        this.currentMap = map;

        int width = map.meta.width, height = map.meta.height;

        createTiles(width, height);

        EntityPhysics.resizeTree(0, 0, width * tilesize, height * tilesize);

        try{
            generator.loadTileData(tiles, MapIO.readTileData(map, true), map.meta.hasOreGen(), 0);
        } catch(Exception e){
            Log.err(e);
            if(!headless){
                ui.showError("$text.map.invalid");
                threads.runDelay(() -> state.set(State.menu));
                invalidMap = true;
            }
            generating = false;
            return;
        }

        if(!headless && state.teams.get(players[0].getTeam()).cores.size == 0){
            ui.showError("$text.map.nospawn");
            threads.runDelay(() -> state.set(State.menu));
            invalidMap = true;
        }else{
            invalidMap = false;
        }

        endMapLoad();
    }

    public void notifyChanged(Tile tile){
        if(!generating){
            threads.runDelay(() -> Events.fire(TileChangeEvent.class, tile));
        }
    }

    public void removeBlock(Tile tile){
        if(!tile.block().isMultiblock() && !tile.isLinked()){
            tile.setBlock(Blocks.air);
        }else{
            Tile target = tile.target();
            Array<Tile> removals = target.getLinkedTiles(tempTiles);
            for(Tile toremove : removals){
                //note that setting a new block automatically unlinks it
                if(toremove != null) toremove.setBlock(Blocks.air);
            }
        }
    }

    public void setBlock(Tile tile, Block block, Team team){
        tile.setBlock(block, team);
        if(block.isMultiblock()){
            int offsetx = -(block.size - 1) / 2;
            int offsety = -(block.size - 1) / 2;

            for(int dx = 0; dx < block.size; dx++){
                for(int dy = 0; dy < block.size; dy++){
                    int worldx = dx + offsetx + tile.x;
                    int worldy = dy + offsety + tile.y;
                    if(!(worldx == tile.x && worldy == tile.y)){
                        Tile toplace = world.tile(worldx, worldy);
                        if(toplace != null){
                            toplace.setLinked((byte) (dx + offsetx), (byte) (dy + offsety));
                            toplace.setTeam(team);
                        }
                    }
                }
            }
        }
    }

    /**
     * Raycast, but with world coordinates.
     */
    public GridPoint2 raycastWorld(float x, float y, float x2, float y2){
        return raycast(Mathf.scl2(x, tilesize), Mathf.scl2(y, tilesize),
                Mathf.scl2(x2, tilesize), Mathf.scl2(y2, tilesize));
    }

    /**
     * Input is in block coordinates, not world coordinates.
     *
     * @return null if no collisions found, block position otherwise.
     */
    public GridPoint2 raycast(int x0f, int y0f, int x1, int y1){
        int x0 = x0f;
        int y0 = y0f;
        int dx = Math.abs(x1 - x0);
        int dy = Math.abs(y1 - y0);

        int sx = x0 < x1 ? 1 : -1;
        int sy = y0 < y1 ? 1 : -1;

        int err = dx - dy;
        int e2;
        while(true){

            if(!passable(x0, y0)){
                return Tmp.g1.set(x0, y0);
            }
            if(x0 == x1 && y0 == y1) break;

            e2 = 2 * err;
            if(e2 > -dy){
                err = err - dy;
                x0 = x0 + sx;
            }

            if(e2 < dx){
                err = err + dx;
                y0 = y0 + sy;
            }
        }
        return null;
    }

    public void raycastEachWorld(float x0, float y0, float x1, float y1, Raycaster cons){
        raycastEach(toTile(x0), toTile(y0), toTile(x1), toTile(y1), cons);
    }

    public void raycastEach(int x0f, int y0f, int x1, int y1, Raycaster cons){
        int x0 = x0f;
        int y0 = y0f;
        int dx = Math.abs(x1 - x0);
        int dy = Math.abs(y1 - y0);

        int sx = x0 < x1 ? 1 : -1;
        int sy = y0 < y1 ? 1 : -1;

        int err = dx - dy;
        int e2;
        while(true){

            if(cons.accept(x0, y0)) break;
            if(x0 == x1 && y0 == y1) break;

            e2 = 2 * err;
            if(e2 > -dy){
                err = err - dy;
                x0 = x0 + sx;
            }

            if(e2 < dx){
                err = err + dx;
                y0 = y0 + sy;
            }
        }
    }

    public interface Raycaster{
        boolean accept(int x, int y);
    }
}
