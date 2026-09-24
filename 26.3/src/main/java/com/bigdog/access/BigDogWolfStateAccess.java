package com.bigdog.access;

/** 通过 @Implements 注入到 WolfRenderState 的访问接口（客户端渲染层读取运载标志用） */
public interface BigDogWolfStateAccess {
	boolean hasCargo();

	void setCargo(boolean cargo);
}
