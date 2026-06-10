package io.github.differentialmanifold.jagentharness.spring.web.dto;

public class VirtualFileImportResponse {

    private int imported;

    public VirtualFileImportResponse() {
    }

    public VirtualFileImportResponse(int imported) {
        this.imported = imported;
    }

    public int getImported() {
        return imported;
    }

    public void setImported(int imported) {
        this.imported = imported;
    }
}
