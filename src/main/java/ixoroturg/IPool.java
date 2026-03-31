package ixoroturg;

import java.util.function.Supplier;
import java.util.function.Function;
import java.util.function.Consumer;
/**
 * Hello world!
 */
public class IPool<T> {
	private IPoolEntry[] pool;
	private Supplier<T> generator;
	private Function<T,T> resetFunction;
	private IPool<T> parent = this;
	/**
	 * Factor for scaling when no one pool object available. By default 2.0
	 */
	public float scaleFactor = 2;
	/**
	 * Maximum size of pool. 0 is disable. By default 0
	 */
	public int maxSize = 0;

	IPool(int initSize, Supplier<T> generator, Function<T,T> resetFunction){
		pool = (IPoolEntry[]) new Object[initSize];
		this.generator = generator;
		this.resetFunction = resetFunction;
	}
	
	public synchronized IPoolEntry open(){
		for(int i = 0; i < pool.length; i++){
			if(pool[i] == null){
				pool[i] = new IPoolEntry(generator.get());
				return pool[i];
			}
			if(!pool[i].open){
				pool[i].open = true;
				return pool[i];
			}
		}
		if(maxSize != 0 && pool.length == maxSize){
				try {
					this.wait();
				} catch (InterruptedException e) {
					System.err.println("Error in IPool");
					e.printStackTrace();
				}
				return open();
		}
		
		int newLength = (int)(pool.length * scaleFactor);
		if(newLength == pool.length){
			newLength++;
		}
		IPoolEntry[] newPool = (IPoolEntry[]) new Object[newLength];
		for(int i = 0; i < pool.length; i++){
			newPool[i] = pool[i];
		}
		pool = newPool;
		return open();
	}

	public void async(Consumer<IPoolEntry> action){
		Thread.ofVirtual().start(()->{
			IPoolEntry entry = open();
			action.accept(entry);
			entry.close();
		});
	}

	public void asyncReset(Consumer<IPoolEntry> action){
		Thread.ofVirtual().start(()->{
			IPoolEntry entry = open();
			entry.reset();
			action.accept(entry);
			entry.close();
		});
	}
	
	public class IPoolEntry implements AutoCloseable{
		private IPoolEntry(T value){
			this.value = value;
		}
		public IPoolEntry reset(){
			value = resetFunction.apply(value);
			return this;
		}
		@Override
		public void close(){
			open = false;
			parent.notifyAll();
		}
		private boolean open = true;
		public T value;
	}
}
