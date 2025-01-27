import { ActionPayload, ClientData } from '../../../shared/models';

export interface ContentletArea {
    x: number;
    y: number;
    width: number;
    height: number;
    payload: ActionPayload;
}

export interface ClientContentletArea {
    x: number;
    y: number;
    width: number;
    height: number;
    payload: ClientData;
}

export interface Container {
    x: number;
    y: number;
    width: number;
    height: number;
    contentlets: ContentletArea[];
    payload: ClientData | string;
}

export interface Column {
    x: number;
    y: number;
    width: number;
    height: number;
    containers: Container[];
}

export interface EmaDragItem {
    baseType: string;
    contentType: string;
}

export interface Row {
    x: number;
    y: number;
    width: number;
    height: number;
    columns: Column[];
}

export type EmaPageDropzoneItem = Row | Column | Container | ContentletArea;
