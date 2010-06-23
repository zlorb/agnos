package agnos;

import java.io.*;
import java.util.*;
import java.lang.ref.*;

public class Protocol
{
	public static final int	CMD_PING				= 0;
	public static final int	CMD_INVOKE				= 1;
	public static final int	CMD_QUIT				= 2;
	public static final int	CMD_DECREF				= 3;
	public static final int	CMD_INCREF				= 4;
	public static final int	CMD_HANDSHAKE			= 5;

	public static final int	REPLY_SUCCESS			= 0;
	public static final int	REPLY_PROTOCOL_ERROR	= 1;
	public static final int	REPLY_PACKED_EXCEPTION	= 2;
	public static final int	REPLY_GENERIC_EXCEPTION	= 3;

	public static final int	AGNOS_MAGIC				= 0x5af30cf7;

	public abstract static class PackedException extends Exception
	{
		public PackedException()
		{
		}
	}

	public static class ProtocolError extends Exception
	{
		public ProtocolError(String message)
		{
			super(message);
		}
	}

	public static class HandshakeError extends ProtocolError
	{
		public HandshakeError(String message)
		{
			super(message);
		}
	}

	public static class GenericException extends Exception
	{
		public String	message;
		public String	traceback;

		public GenericException(String message, String traceback)
		{
			this.message = message;
			this.traceback = traceback;
		}

		public String toString()
		{
			return "agnos.Protocol.GenericException: "
					+ message
					+ " with remote backtrace:\n"
					+ traceback
					+ "\t------------------- end of remote traceback -------------------";
		}
	}

	public static class ObjectIDGenerator
	{
		protected Map<Object, Long>	map;
		protected Long				counter;

		public ObjectIDGenerator()
		{
			map = new WeakHashMap<Object, Long>();
			counter = new Long(0);
		}

		public synchronized Long getID(Object obj)
		{
			Object id = map.get(obj);
			if (id != null) {
				return (Long) id;
			}
			else {
				counter += 1;
				map.put(obj, counter);
				return counter;
			}
		}
	}

	protected static class Cell
	{
		public int		refcount;
		public Object	obj;

		public Cell(Object obj)
		{
			refcount = 1;
			this.obj = obj;
		}

		public void incref()
		{
			refcount += 1;
		}

		public boolean decref()
		{
			refcount -= 1;
			return refcount <= 0;
		}
	}

	public static abstract class BaseProcessor implements Packers.ISerializer
	{
		protected Map<Long, Cell>	cells;
		protected ObjectIDGenerator	idGenerator;

		public BaseProcessor()
		{
			cells = new HashMap<Long, Cell>();
			idGenerator = new ObjectIDGenerator();
		}

		public Long store(Object obj)
		{
			if (obj == null) {
				return new Long(-1);
			}
			Long id = idGenerator.getID(obj);
			Cell cell = cells.get(id);
			if (cell == null) {
				cell = new Cell(obj);
				cells.put(id, cell);
			}
			// else {
			// cell.incref();
			// }
			return id;
		}

		public Object load(Long id)
		{
			if (id < 0) {
				return null;
			}
			Cell cell = cells.get(id);
			return cell.obj;
		}

		protected void incref(Long id)
		{
			Cell cell = cells.get(id);
			if (cell != null) {
				cell.incref();
			}
		}

		protected void decref(Long id)
		{
			Cell cell = cells.get(id);
			if (cell != null) {
				if (cell.decref()) {
					cells.remove(id);
				}
			}
		}

		protected static String getExceptionTraceback(Exception exc)
		{
			StringWriter sw = new StringWriter(2000);
			PrintWriter pw = new PrintWriter(sw, true);
			exc.printStackTrace(pw);
			pw.flush();
			sw.flush();
			String[] lines = sw.toString().split("\r\n|\r|\n");
			StringWriter sw2 = new StringWriter(2000);
			// drop first line, it's the message, not traceback
			for (int i = 1; i < lines.length; i++) {
				sw2.write(lines[i]);
				sw2.write("\n");
			}
			return sw2.toString();
		}

		protected void send_protocol_error(Transports.ITransport transport,
				ProtocolError exc) throws IOException
		{
			Packers.Int8.pack((byte) REPLY_PROTOCOL_ERROR, transport);
			Packers.Str.pack(exc.toString(), transport);
		}

		protected void send_generic_exception(Transports.ITransport transport,
				GenericException exc) throws IOException
		{
			Packers.Int8.pack((byte) REPLY_GENERIC_EXCEPTION, transport);
			Packers.Str.pack(exc.message, transport);
			Packers.Str.pack(exc.traceback, transport);
		}

		protected void process(Transports.ITransport transport)
				throws Exception
		{
			int seq = transport.beginRead();
			int cmdid = (Byte) (Packers.Int8.unpack(transport));

			transport.beginWrite(seq);
			
			try {
				switch (cmdid) {
					case CMD_INVOKE:
						process_invoke(transport, seq);
						break;
					case CMD_DECREF:
						process_decref(transport, seq);
						break;
					case CMD_INCREF:
						process_incref(transport, seq);
						break;
					case CMD_QUIT:
						process_quit(transport, seq);
						break;
					case CMD_PING:
						process_ping(transport, seq);
						break;
					default:
						throw new ProtocolError("unknown command code: "
								+ cmdid);
				}
			} catch (ProtocolError exc) {
				transport.reset();
				send_protocol_error(transport, exc);
			} catch (GenericException exc) {
				transport.reset();
				send_generic_exception(transport, exc);
			} catch (Exception ex) {
				transport.cancelWrite();
				throw ex;
			} finally {
				transport.endRead();
			}
			transport.endWrite();
		}

		protected void process_decref(Transports.ITransport transport,
				Integer seq) throws IOException
		{
			Long id = (Long) (Packers.Int64.unpack(transport));
			decref(id);
		}

		protected void process_incref(Transports.ITransport transport,
				Integer seq) throws IOException
		{
			Long id = (Long) (Packers.Int64.unpack(transport));
			incref(id);
		}

		protected void process_quit(Transports.ITransport transport, Integer seq)
				throws IOException
		{
		}

		protected void process_ping(Transports.ITransport transport, Integer seq)
				throws IOException
		{
			String message = (String) (Packers.Str.unpack(transport));
			Packers.Int8.pack(REPLY_SUCCESS, transport);
			Packers.Str.pack(message, transport);
		}

		abstract protected void process_invoke(Transports.ITransport transport,
				int seq) throws Exception;
	}

	protected enum ReplySlotType
	{
		SLOT_EMPTY, SLOT_DISCARDED, SLOT_VALUE, SLOT_EXCEPTION
	}

	protected static class ReplySlot
	{
		public ReplySlotType	type;
		public Object			value;

		public ReplySlot(Packers.BasePacker packer)
		{
			type = ReplySlotType.SLOT_EMPTY;
			value = packer;
		}
	}

	public static abstract class BaseClient
	{
		protected static class _BaseClientUtils
		{
			protected Map<Integer, Packers.BasePacker> packedExceptionsMap;
			protected int						seq;
			protected Map<Integer, ReplySlot>	replies;
			protected Map<Long, WeakReference>	proxies;
			public Transports.ITransport		transport;
	
			public _BaseClientUtils(Transports.ITransport transport, 
						Map<Integer, Packers.BasePacker> packedExceptionsMap)
					throws Exception
			{
				this.transport = transport;
				this.packedExceptionsMap = packedExceptionsMap;
				seq = 0;
				replies = new HashMap<Integer, ReplySlot>(128);
				proxies = new HashMap<Long, WeakReference>();
			}
	
			public void close() throws IOException
			{
				if (transport != null) {
					transport.close();
					transport = null;
				}
			}
	
			public synchronized int getSeq()
			{
				seq += 1;
				return seq;
			}
	
			public Object getProxy(Long objref)
			{
				WeakReference weak = proxies.get(objref);
				if (weak == null) {
					return null;
				}
				Object proxy = weak.get();
				if (proxy == null) {
					proxies.remove(objref);
					return null;
				}
				return proxy;
			}
	
			public void decref(Long id)
			{
				int seq = getSeq();
				try {
					Packers.Int8.pack(CMD_DECREF, transport);
					Packers.Int64.pack(id, transport);
				} catch (Exception ignored) {
					// ignored
				}
			}
	
			public int beginCall(int funcid, Packers.BasePacker packer)
					throws IOException
			{
				int seq = getSeq();
				transport.beginWrite(seq);
				Packers.Int8.pack(CMD_INVOKE, transport);
				Packers.Int32.pack(funcid, transport);
				replies.put(seq, new ReplySlot(packer));
				return seq;
			}

			public void endCall() throws IOException
			{
				transport.endWrite();
			}

			public void cancelCall() throws IOException
			{
				transport.endWrite();
			}

			public PackedException loadPackedException() throws IOException, ProtocolError
	        {
	            Integer clsid = (Integer)Packers.Int32.unpack(transport);
	            Packers.BasePacker packer = packedExceptionsMap.get(clsid);
	            if (packer == null) {
	            	throw new Protocol.ProtocolError("unknown exception class id: " + clsid);
	            }
	            return (PackedException)packer.unpack(transport);
	        }
	        
			public ProtocolError loadProtocolError() throws IOException
			{
				String message = (String) Packers.Str.unpack(transport);
				return new ProtocolError(message);
			}
	
			public GenericException loadGenericException() throws IOException
			{
				String message = (String) Packers.Str.unpack(transport);
				String traceback = (String) Packers.Str.unpack(transport);
				return new GenericException(message, traceback);
			}
	
			public boolean processIncoming(int timeout_msecs) throws Exception
			{
				int seq = transport.beginRead();
	
				try {
					int code = (Byte) (Packers.Int8.unpack(transport));
					ReplySlot slot = replies.get(seq);
					
					if (slot == null || (slot.type != ReplySlotType.SLOT_EMPTY && 
							slot.type != ReplySlotType.SLOT_DISCARDED)) {
						throw new ProtocolError("invalid reply sequence: " + seq);
					}
					Packers.BasePacker packer = (Packers.BasePacker) slot.value;
	
					switch (code) {
					case REPLY_SUCCESS:
						if (packer == null) {
							slot.value = null;
						}
						else {
							slot.value = packer.unpack(transport);
						}
						slot.type = ReplySlotType.SLOT_VALUE;
						break;
					case REPLY_PROTOCOL_ERROR:
						throw (ProtocolError) (loadProtocolError().fillInStackTrace());
					case REPLY_PACKED_EXCEPTION:
						slot.type = ReplySlotType.SLOT_EXCEPTION;
						slot.value = loadPackedException();
						break;
					case REPLY_GENERIC_EXCEPTION:
						slot.type = ReplySlotType.SLOT_EXCEPTION;
						slot.value = loadGenericException();
						break;
					default:
						throw new ProtocolError("unknown reply code: " + code);
					}
				} finally {
					transport.endRead();
				}
	
				return true;
			}
	
			public boolean isReplyReady(int seq)
			{
				ReplySlot slot = replies.get(seq);
				return slot.type == ReplySlotType.SLOT_VALUE
						|| slot.type == ReplySlotType.SLOT_EXCEPTION;
			}
	
			public ReplySlot waitReply(int seq, int timeout_msecs)
					throws Exception
			{
				while (!isReplyReady(seq)) {
					processIncoming(timeout_msecs);
				}
				return replies.remove(seq);
			}
	
			public Object getReply(int seq, int timeout_msecs) throws Exception
			{
				ReplySlot slot = waitReply(seq, timeout_msecs);
				if (slot.type == ReplySlotType.SLOT_VALUE) {
					return slot.value;
				}
				else if (slot.type == ReplySlotType.SLOT_EXCEPTION) {
					((Exception) slot.value).fillInStackTrace();
					throw (Exception) slot.value;
				}
				else {
					throw new Exception("invalid slot type: " + slot.type);
				}
			}
	
			public Object getReply(int seq) throws Exception
			{
				return getReply(seq, -1);
			}
		}
		
		_BaseClientUtils _utils;
	}

}
