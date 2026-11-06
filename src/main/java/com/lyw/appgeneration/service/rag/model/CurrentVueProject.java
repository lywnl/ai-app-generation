package com.lyw.appgeneration.service.rag.model;

/** 本轮安全识别到的工程目录状态；未知工程不视为空工程。 */
public record CurrentVueProject(Status status, String reason) {

    public static CurrentVueProject unavailable(String reason) {
        return new CurrentVueProject(Status.UNAVAILABLE, reason);
    }

    public boolean available() { return status == Status.AVAILABLE; }
    public boolean existing() { return status != Status.ABSENT; }

    public enum Status { ABSENT, AVAILABLE, UNAVAILABLE }
}
