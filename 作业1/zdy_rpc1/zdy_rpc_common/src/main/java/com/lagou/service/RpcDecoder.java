package com.lagou.service;

import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.ByteToMessageDecoder;

import java.util.List;


public class RpcDecoder extends ByteToMessageDecoder{
	private Class<?> clazz;
	private Serializer serializer;

	public RpcDecoder(Class<?> clazz, Serializer serializer) {
		this.clazz = clazz;
		this.serializer = serializer;
	}
	@Override
	protected void decode(ChannelHandlerContext channelHandlerContext, ByteBuf byteBuf, List<Object> list) throws Exception {
		//因为之前编码的时候写入一个Int型，4个字节来表示长度
		if (byteBuf.readableBytes() < 4) {
			return;
		}
		//标记一下当前的readIndex的位置
		byteBuf.markReaderIndex();
		// 读取传送过来的消息的长度。ByteBuf 的readInt()方法会让他的readIndex增加4
		int dataLength = byteBuf.readInt();
		//读到的消息体长度如果小于我们传送过来的消息长度，则resetReaderIndex. 这个配合markReaderIndex使用的。把readIndex重置到mark的地方
		if (byteBuf.readableBytes() < dataLength) {
			byteBuf.resetReaderIndex();
			return;
		}
		byte[] data = new byte[dataLength];
		//将byteBuf中的数据读入data字节数组
		byteBuf.readBytes(data);
		Object obj = serializer.deserialize(clazz, data);
		list.add(obj);
	}
}
